package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.application.BookingService;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.RideOfferRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds a driver for every ride that needs one, by offering it to the nearest free driver and waiting for an
 * answer. Designed to run on SEVERAL service instances at once without ever double-offering:
 * <ul>
 *   <li>the slow part (asking driver-service who is nearby) happens with no database lock held;</li>
 *   <li>a short transaction then locks the ride with SKIP LOCKED, re-checks it, reserves the driver in Redis
 *       and inserts the offer: an instance that finds the ride locked simply moves on;</li>
 *   <li>unique indexes in the database guarantee one live offer per ride and per driver even if Redis were wrong.</li>
 * </ul>
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final int RIDES_PER_TICK = 20;
    private static final int EXPIRIES_PER_TICK = 50;
    /** The Redis hold outlives the offer a little, so it can never lapse while the offer is still answerable. */
    private static final Duration RESERVATION_GRACE = Duration.ofSeconds(5);

    private final BookingRepository bookings;
    private final RideOfferRepository offers;
    private final DriverDirectory directory;
    private final DriverReservations reservations;
    private final MatchingProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final BookingService bookingService;

    public MatchingService(BookingService bookingService, BookingRepository bookings, RideOfferRepository offers, DriverDirectory directory,
                           DriverReservations reservations, MatchingProperties props, Clock clock, TransactionTemplate tx) {
        this.bookings = bookings;
        this.offers = offers;
        this.directory = directory;
        this.reservations = reservations;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
        this.bookingService = bookingService;
    }

    /** One matching pass: expire stale offers, then offer every waiting ride to a driver. Returns offers created. */
    public int tick() {
        expireDueOffers();
        expireUnmatchedRides();
        int created = 0;
        for (Booking ride : bookings.findDispatchCandidates(clock.instant(), RIDES_PER_TICK)) {
            try {
                if (offerToNearestFreeDriver(ride)) {
                    created++;
                }
            } catch (DriverDirectoryException e) {
                log.warn("Driver lookup failed for a ride; retrying shortly: {}", e.getMessage());
                postpone(ride.getId());
            } catch (RuntimeException e) {
                log.warn("Matching a ride failed; it will be retried on a later pass", e);
            }
        }
        return created;
    }

    /** A ride nobody accepted within the search window is closed, so the rider is told rather than left waiting. */
    void expireUnmatchedRides() {
        Instant cutoff = clock.instant().minus(props.unmatchedTimeout());
        for (Booking ride : bookings.findUnmatchedSince(cutoff, EXPIRIES_PER_TICK)) {
            try {
                bookingService.expireIfUnmatched(ride.getId(), cutoff);
            } catch (RuntimeException e) {
                log.warn("Could not expire an unmatched ride; it will be retried on a later pass", e);
            }
        }
    }

    /** Offers that ran out of time go back to the pool: the driver is freed and the ride is matched again. */
    void expireDueOffers() {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            for (RideOffer offer : offers.lockDue(now, EXPIRIES_PER_TICK)) {
                if (offer.expire(now)) {
                    offers.save(offer);
                    bookings.deferDispatch(offer.getBookingId(), now); // eligible again immediately
                    AfterTransaction.onCommit(() -> reservations.release(offer.getDriverId(), offer.getBookingId()));
                }
            }
        });
    }

    private boolean offerToNearestFreeDriver(Booking candidate) {
        // 1) the slow network call, with NO lock held
        List<NearbyDriver> nearby = directory.nearby(candidate.getServiceType(), candidate.getPickup().lat(),
                candidate.getPickup().lng(), props.radiusKm(), props.candidateLimit());

        // 2) one short transaction decides and writes
        Boolean offered = tx.execute(status -> {
            Instant now = clock.instant();
            Booking ride = bookings.lockForDispatch(candidate.getId(), now).orElse(null);
            if (ride == null) {
                return false; // another instance has it, someone accepted/cancelled it, or an offer is already live
            }
            Set<UUID> alreadyAsked = offers.driverIdsOfferedTo(ride.getId());
            Set<UUID> busy = nearby.isEmpty() ? Set.of() : bookings.findBusyDrivers(nearby.stream().map(NearbyDriver::driverId).toList());

            for (NearbyDriver driver : nearby) {
                if (alreadyAsked.contains(driver.driverId()) || busy.contains(driver.driverId())) {
                    continue;
                }
                if (!reservations.tryReserve(driver.driverId(), ride.getId(), props.offerTtl().plus(RESERVATION_GRACE))) {
                    continue; // this driver is being offered another ride right now
                }
                // if anything below fails and the transaction rolls back, the driver must not stay reserved
                AfterTransaction.onRollback(() -> reservations.release(driver.driverId(), ride.getId()));
                offers.saveAndFlush(RideOffer.offer(ride.getId(), driver.driverId(), driver.distanceKm(), now, props.offerTtl()));
                return true;
            }
            ride.deferDispatchUntil(now.plus(props.retryDelay())); // nobody suitable: look again in a few seconds
            bookings.save(ride);
            return false;
        });
        return Boolean.TRUE.equals(offered);
    }

    private void postpone(UUID bookingId) {
        tx.executeWithoutResult(status -> bookings.deferDispatch(bookingId, clock.instant().plus(props.retryDelay())));
    }
}
