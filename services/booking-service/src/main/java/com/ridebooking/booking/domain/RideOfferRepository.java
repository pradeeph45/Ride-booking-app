package com.ridebooking.booking.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideOfferRepository extends JpaRepository<RideOffer, UUID> {

    /**
     * Loads one offer and locks its row until the transaction ends (SELECT ... FOR UPDATE), so an accept, a
     * reject and the expiry sweep on the same offer take turns instead of overwriting each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RideOffer o where o.id = :id")
    Optional<RideOffer> lockById(@Param("id") UUID id);

    /** The live offer for a ride, locked, used when the rider cancels. Lock order is always offer then booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RideOffer o where o.bookingId = :bookingId and o.status = com.ridebooking.booking.domain.OfferStatus.PENDING")
    List<RideOffer> lockPendingForBooking(@Param("bookingId") UUID bookingId);

    /**
     * Pending offers past their deadline, locked with SKIP LOCKED: several service instances can sweep at once and
     * each takes a different batch, and an offer being accepted right now is simply skipped.
     */
    @Query(value = "SELECT id, booking_id, driver_id, status, pickup_distance_km, offered_at, expires_at, responded_at, version "
            + "FROM ride_offers WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<RideOffer> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Query("select o from RideOffer o where o.driverId = :driverId and o.status = com.ridebooking.booking.domain.OfferStatus.PENDING and o.expiresAt > :now order by o.offeredAt")
    List<RideOffer> findLiveForDriver(@Param("driverId") UUID driverId, @Param("now") Instant now);

    /** Every driver this ride has ever been offered to, so nobody is asked twice about the same ride. */
    @Query("select o.driverId from RideOffer o where o.bookingId = :bookingId")
    Set<UUID> driverIdsOfferedTo(@Param("bookingId") UUID bookingId);

    List<RideOffer> findByBookingId(UUID bookingId);

    long countByStatus(OfferStatus status);

    @Query("select count(o) from RideOffer o where o.driverId in :ids and o.status = com.ridebooking.booking.domain.OfferStatus.PENDING")
    long countPendingForDrivers(@Param("ids") Collection<UUID> ids);
}
