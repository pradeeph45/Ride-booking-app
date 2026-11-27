package com.ridebooking.booking.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /** The booking a rider already created with this Idempotency-Key, if any. Keys are scoped per rider. */
    Optional<Booking> findByRiderIdAndIdempotencyKey(UUID riderId, String idempotencyKey);

    /**
     * Rides that need a driver right now: still REQUESTED, due for another attempt, and with no live offer.
     * A plain read (no lock): it only nominates candidates; each one is re-checked under a lock before use.
     */
    @Query(value = "SELECT b.* FROM bookings b WHERE b.status = 'REQUESTED' "
            + "AND (b.dispatch_after IS NULL OR b.dispatch_after <= :now) "
            + "AND NOT EXISTS (SELECT 1 FROM ride_offers o WHERE o.booking_id = b.id AND o.pending_booking_id IS NOT NULL) "
            + "ORDER BY b.created_at LIMIT :limit", nativeQuery = true)
    List<Booking> findDispatchCandidates(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Re-checks and locks ONE ride for dispatch. SKIP LOCKED means: if another instance (or an accept, or a cancel)
     * holds this row right now, return nothing instead of waiting, and let that other party finish.
     */
    @Query(value = "SELECT b.* FROM bookings b WHERE b.id = :id AND b.status = 'REQUESTED' "
            + "AND (b.dispatch_after IS NULL OR b.dispatch_after <= :now) "
            + "AND NOT EXISTS (SELECT 1 FROM ride_offers o WHERE o.booking_id = b.id AND o.pending_booking_id IS NOT NULL) "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Booking> lockForDispatch(@Param("id") UUID id, @Param("now") Instant now);

    /** Rides that have waited longer than the search window. A plain read: each is re-checked under a lock. */
    @Query(value = "SELECT b.* FROM bookings b WHERE b.status = 'REQUESTED' AND b.created_at <= :cutoff "
            + "ORDER BY b.created_at LIMIT :limit", nativeQuery = true)
    List<Booking> findUnmatchedSince(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Locks one still-waiting ride for expiry; SKIP LOCKED so a concurrent accept or cancel simply wins. */
    @Query(value = "SELECT b.* FROM bookings b WHERE b.id = :id AND b.status = 'REQUESTED' AND b.created_at <= :cutoff "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Booking> lockForExpiry(@Param("id") UUID id, @Param("cutoff") Instant cutoff);

    /** Of these drivers, the ones who already have a ride in progress and so must not be offered another. */
    @Query("select distinct b.driverId from Booking b where b.driverId in :ids and b.status in "
            + "(com.ridebooking.booking.domain.BookingStatus.MATCHED, com.ridebooking.booking.domain.BookingStatus.ARRIVED, "
            + "com.ridebooking.booking.domain.BookingStatus.STARTED)")
    Set<UUID> findBusyDrivers(@Param("ids") Collection<UUID> ids);

    /** Makes a ride eligible for matching again at once (after an offer expired or was declined). */
    @Modifying
    @Query("update Booking b set b.dispatchAfter = :when where b.id = :id and b.status = com.ridebooking.booking.domain.BookingStatus.REQUESTED")
    int deferDispatch(@Param("id") UUID id, @Param("when") Instant when);
}
