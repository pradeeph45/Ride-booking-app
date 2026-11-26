package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.application.BookingService;
import com.ridebooking.booking.application.CurrentUser;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingAccessDeniedException;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.OfferNotFoundException;
import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.RideOfferRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a driver does with an offer. Both actions lock the offer row first (SELECT ... FOR UPDATE), so an accept,
 * a reject and the expiry sweep on the same offer take turns, and all of them repeat safely.
 */
@Service
public class OfferService {

    private final RideOfferRepository offers;
    private final BookingRepository bookingRepository;
    private final BookingService bookings;
    private final DriverReservations reservations;
    private final Clock clock;

    public OfferService(RideOfferRepository offers, BookingRepository bookingRepository, BookingService bookings,
                        DriverReservations reservations, Clock clock) {
        this.offers = offers;
        this.bookingRepository = bookingRepository;
        this.bookings = bookings;
        this.reservations = reservations;
        this.clock = clock;
    }

    /** The driver's live offers (normally zero or one), with ride details. */
    @Transactional(readOnly = true)
    public List<OfferView> liveOffers(CurrentUser driver) {
        requireDriver(driver);
        List<RideOffer> live = offers.findLiveForDriver(driver.id(), clock.instant());
        Map<UUID, Booking> rides = bookingRepository.findAllById(live.stream().map(RideOffer::getBookingId).toList()).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        return live.stream().map(o -> new OfferView(o, rides.get(o.getBookingId()))).toList();
    }

    /**
     * Accepting assigns the driver to the ride in the SAME transaction that marks the offer accepted, so there is no
     * moment where one is true without the other. If the rider cancelled in the meantime the assignment fails, the
     * whole thing rolls back and the driver gets a clean conflict.
     */
    @Transactional
    public Booking accept(UUID offerId, CurrentUser driver) {
        requireDriver(driver);
        RideOffer offer = ownOffer(offerId, driver);
        boolean changed = offer.accept(clock.instant());               // throws if expired / withdrawn; repeat = no-op
        Booking ride = bookings.accept(offer.getBookingId(), driver);
        if (changed) {
            offers.save(offer);
            AfterTransaction.onCommit(() -> reservations.release(offer.getDriverId(), offer.getBookingId()));
        }
        return ride;
    }

    @Transactional
    public void reject(UUID offerId, CurrentUser driver) {
        requireDriver(driver);
        RideOffer offer = ownOffer(offerId, driver);
        Instant now = clock.instant();
        if (offer.reject(now)) {
            offers.save(offer);
            bookingRepository.deferDispatch(offer.getBookingId(), now);   // find the next driver straight away
            AfterTransaction.onCommit(() -> reservations.release(offer.getDriverId(), offer.getBookingId()));
        }
    }

    /** Someone else's offer looks exactly like one that does not exist. */
    private RideOffer ownOffer(UUID offerId, CurrentUser driver) {
        return offers.lockById(offerId).filter(o -> o.getDriverId().equals(driver.id())).orElseThrow(OfferNotFoundException::new);
    }

    private static void requireDriver(CurrentUser user) {
        if (user.role() != CurrentUser.Role.DRIVER) {
            throw new BookingAccessDeniedException("Requires role DRIVER");
        }
    }
}
