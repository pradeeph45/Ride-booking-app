package com.ridebooking.booking.application;

import com.ridebooking.booking.application.matching.AfterTransaction;
import com.ridebooking.booking.application.matching.OfferCleanup;
import com.ridebooking.booking.application.metering.FareSettlement;
import com.ridebooking.booking.application.metering.TripMeter;
import com.ridebooking.booking.application.tracking.TrackingRegistry;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingAccessDeniedException;
import com.ridebooking.booking.domain.BookingNotFoundException;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.BookingStatusChange;
import com.ridebooking.booking.domain.BookingStatusChangeRepository;
import com.ridebooking.booking.domain.FareSource;
import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Use cases for bookings. Who the caller is always comes from {@link CurrentUser} (the verified token);
 * rules about WHAT may happen live in the {@link Booking} aggregate.
 *
 * <p>Concurrency: every state change is a read-modify-write protected by the booking's @Version column.
 * If two requests change the same booking at once, the database lets one commit and the other fails with
 * an optimistic-locking error, which the API reports as 409. Nothing is held in memory, so this works
 * identically across many service instances.
 *
 * <p>Retries: creating a booking is idempotent per (rider, Idempotency-Key), and repeating a state change
 * that already happened is a no-op, so a client that never saw the response can safely try again.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    static final int MAX_PAGE = 50;

    private final BookingRepository bookings;
    private final BookingStatusChangeRepository history;
    private final FareCalculator fares;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final OfferCleanup offerCleanup;
    private final TrackingRegistry tracking;
    private final TripMeter meter;
    private final FareSettlement settlement;

    public BookingService(BookingRepository bookings, BookingStatusChangeRepository history,
                          FareCalculator fares, Clock clock, TransactionTemplate tx, OfferCleanup offerCleanup,
                          TrackingRegistry tracking, TripMeter meter, FareSettlement settlement) {
        this.bookings = bookings;
        this.history = history;
        this.fares = fares;
        this.clock = clock;
        this.tx = tx;
        this.offerCleanup = offerCleanup;
        this.tracking = tracking;
        this.meter = meter;
        this.settlement = settlement;
    }

    /** A price for every service type, so the rider can choose before booking. */
    public List<FareQuote> estimate(Place pickup, Place drop) {
        return Arrays.stream(ServiceType.values()).map(type -> fares.estimate(type, pickup, drop)).toList();
    }

    /**
     * Creates a booking, or, when the same rider retries with the same Idempotency-Key, returns the one
     * already created. The unique index on (rider_id, idempotency_key) is the real guard: if two retries
     * race, one insert wins and the loser's insert fails. That failure poisons its transaction, so the
     * loser looks the winner's booking up again in a fresh transaction and returns it.
     *
     * @param idempotencyKey optional; without it every call creates a new booking
     */
    public CreateResult create(CurrentUser rider, ServiceType type, Place pickup, Place drop, String idempotencyKey) {
        requireRole(rider, CurrentUser.Role.RIDER);
        String key = IdempotencyKey.validated(idempotencyKey);
        String fingerprint = key == null ? null : RequestFingerprint.of(type, pickup, drop);
        try {
            return tx.execute(status -> createOnce(rider, type, pickup, drop, key, fingerprint));
        } catch (DataIntegrityViolationException | ConcurrencyFailureException lostRace) {
            if (key == null) {
                throw lostRace; // nothing to deduplicate on: a genuine failure
            }
            return tx.execute(status -> replay(rider, key, fingerprint).orElseThrow(() -> lostRace));
        }
    }

    private CreateResult createOnce(CurrentUser rider, ServiceType type, Place pickup, Place drop,
                                    String key, String fingerprint) {
        if (key != null) {
            Optional<CreateResult> existing = replay(rider, key, fingerprint);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Instant now = clock.instant();
        FareQuote quote = fares.estimate(type, pickup, drop); // price is decided here, never trusted from the client
        Booking booking = bookings.saveAndFlush(Booking.request(rider.id(), type, pickup, drop, quote, now, key, fingerprint));
        history.save(BookingStatusChange.of(booking.getId(), null, BookingStatus.REQUESTED, rider.id(), now));
        return new CreateResult(booking, false);
    }

    private Optional<CreateResult> replay(CurrentUser rider, String key, String fingerprint) {
        return bookings.findByRiderIdAndIdempotencyKey(rider.id(), key).map(existing -> {
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new IdempotencyKeyReusedException(); // same key, different request: a client bug, not a retry
            }
            return new CreateResult(existing, true);
        });
    }

    @Transactional(readOnly = true)
    public Booking get(UUID id, CurrentUser user) {
        Booking booking = bookings.findById(id).orElseThrow(BookingNotFoundException::new);
        if (!booking.involves(user.id())) {
            throw new BookingNotFoundException(); // same answer as "does not exist": no probing of other people's ids
        }
        return booking;
    }

    /** The caller's own bookings (as rider, or as driver), newest first, in keyset-paginated pages. */
    @Transactional(readOnly = true)
    public BookingPage history(CurrentUser user, BookingStatus status, String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        BookingCursor after = cursor == null || cursor.isBlank() ? null : BookingCursor.decode(cursor);
        String ownerColumn = user.role() == CurrentUser.Role.DRIVER ? "driverId" : "riderId";

        Specification<Booking> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get(ownerColumn), user.id())); // always scoped to the caller
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (after != null) {
                where.add(cb.or(
                        cb.lessThan(root.get("createdAt"), after.createdAt()),
                        cb.and(cb.equal(root.get("createdAt"), after.createdAt()),
                                cb.lessThan(root.<UUID>get("id"), after.id()))));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };

        // Ask for one extra row: if it exists there is a next page, without a COUNT query.
        List<Booking> rows = bookings.findBy(spec, q -> q
                .sortBy(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                .limit(size + 1)
                .all());
        boolean more = rows.size() > size;
        List<Booking> items = more ? rows.subList(0, size) : rows;
        String next = more ? new BookingCursor(items.get(size - 1).getCreatedAt(), items.get(size - 1).getId()).encode() : null;
        return new BookingPage(List.copyOf(items), next);
    }

    @Transactional
    public Booking cancel(UUID id, CurrentUser rider) {
        Booking booking = bookings.findById(id).filter(b -> b.getRiderId().equals(rider.id()))
                .orElseThrow(BookingNotFoundException::new);
        offerCleanup.cancelPendingFor(id); // lock any live offer FIRST (offer, then booking: the same order accept uses)
        return change(booking, rider.id(), (b, now) -> b.cancelByRider(rider.id(), now));
    }

    @Transactional
    public Booking accept(UUID id, CurrentUser driver) {
        requireRole(driver, CurrentUser.Role.DRIVER);
        Booking booking = bookings.findById(id).orElseThrow(BookingNotFoundException::new);
        return change(booking, driver.id(), (b, now) -> b.assignDriver(driver.id(), now));
    }

    @Transactional
    public Booking arrive(UUID id, CurrentUser driver) {
        return driverStep(id, driver, (b, now) -> b.driverArrived(driver.id(), now));
    }

    @Transactional
    public Booking start(UUID id, CurrentUser driver) {
        Booking started = driverStep(id, driver, (b, now) -> b.start(driver.id(), now));
        // after commit, like the tracking registry: the meter never runs for a trip that did not really start
        AfterTransaction.onCommit(() -> startMeter(started));
        return started;
    }

    /**
     * Ends the trip. The fare is what was actually driven when the trip meter has enough data, otherwise the quoted
     * estimate. Repeating the call changes nothing, including the fare.
     */
    @Transactional
    public Booking complete(UUID id, CurrentUser driver) {
        Booking done = driverStep(id, driver, (b, now) -> {
            Optional<MeteredFare> metered = settlement.settle(b, now);
            return metered
                    .map(m -> b.complete(driver.id(), now, m.fare(), m.distanceKm(), m.minutes(), FareSource.METERED))
                    .orElseGet(() -> b.complete(driver.id(), now));
        });
        AfterTransaction.onCommit(() -> meter.discard(id));
        return done;
    }

    private void startMeter(Booking started) {
        try {
            meter.begin(started.getId(), started.getUpdatedAt());
        } catch (RuntimeException e) {
            log.warn("Could not start the trip meter for booking {}; it will be completed at the estimate", started.getId(), e);
        }
    }

    private Booking driverStep(UUID id, CurrentUser driver, BiFunction<Booking, Instant, Boolean> action) {
        requireRole(driver, CurrentUser.Role.DRIVER);
        Booking booking = bookings.findById(id)
                .filter(b -> driver.id().equals(b.getDriverId()))
                .orElseThrow(BookingNotFoundException::new); // someone else's trip looks like a missing one
        return change(booking, driver.id(), action);
    }

    /**
     * Applies a transition and records it in the audit trail, atomically with the booking update.
     * If the action reports no change (a retry of something already done) nothing is written at all.
     *
     * <p>LOCK ORDER MATTERS: the booking UPDATE is flushed BEFORE the history INSERT. The history row has a
     * foreign key to the booking, so inserting it first takes a shared lock on the booking row; two racing
     * transactions would then both hold a shared lock and both wait for an exclusive one, which is a
     * deadlock (MySQL aborts one with a 500). Updating first takes the exclusive lock up front, so racing
     * transactions simply queue, and the loser fails the version check with a clean 409.
     */
    /** Actor recorded in the audit trail for changes made by the system itself (all zeros: not a real user). */
    public static final UUID SYSTEM = new UUID(0L, 0L);

    /**
     * Closes a ride nobody accepted. Offer first, then booking (the lock order accept and cancel use). If the ride
     * is no longer waiting (accepted or cancelled a moment ago) nothing is changed, not even its offers.
     *
     * @return true if the ride was expired by this call
     */
    @Transactional
    public boolean expireIfUnmatched(UUID id, Instant cutoff) {
        offerCleanup.cancelPendingFor(id);
        Booking ride = bookings.lockForExpiry(id, cutoff).orElse(null);
        if (ride == null) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return false;
        }
        change(ride, SYSTEM, (b, now) -> b.expire(now));
        return true;
    }

    private Booking change(Booking booking, UUID actorId, BiFunction<Booking, Instant, Boolean> action) {
        BookingStatus before = booking.getStatus();
        Instant now = clock.instant();
        if (!action.apply(booking, now)) {
            return booking; // a repeat: no write, no audit row
        }
        Booking saved = bookings.saveAndFlush(booking);
        history.save(BookingStatusChange.of(saved.getId(), before, saved.getStatus(), actorId, now));
        // tell the live-tracking service once (and only if) the change is really committed
        AfterTransaction.onCommit(() -> tracking.publish(saved));
        return saved;
    }

    private static void requireRole(CurrentUser user, CurrentUser.Role required) {
        if (user.role() != required) {
            throw new BookingAccessDeniedException("Requires role " + required);
        }
    }
}
