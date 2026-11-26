package com.ridebooking.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Driver D, would you take ride B? You have until expires_at." Every transition reports whether anything
 * changed, so repeating an answer (a retry after a lost response) is a harmless no-op.
 */
@Entity
@Table(name = "ride_offers")
public class RideOffer {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID bookingId;

    @Column(name = "driver_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OfferStatus status;

    @Column(name = "pickup_distance_km", nullable = false, updatable = false, precision = 6, scale = 2)
    private BigDecimal pickupDistanceKm;

    @Column(name = "offered_at", nullable = false, updatable = false)
    private Instant offeredAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Version
    private long version;

    protected RideOffer() {
        // for JPA
    }

    public static RideOffer offer(UUID bookingId, UUID driverId, double pickupDistanceKm, Instant now, Duration ttl) {
        RideOffer o = new RideOffer();
        o.id = UUID.randomUUID();
        o.bookingId = bookingId;
        o.driverId = driverId;
        o.status = OfferStatus.PENDING;
        o.pickupDistanceKm = BigDecimal.valueOf(pickupDistanceKm).setScale(2, RoundingMode.HALF_UP);
        o.offeredAt = now;
        o.expiresAt = now.plus(ttl);
        return o;
    }

    /**
     * @return true if the offer was just accepted, false if it already was (a retry)
     * @throws OfferNotActiveException if it expired or was rejected / cancelled
     */
    public boolean accept(Instant now) {
        if (status == OfferStatus.ACCEPTED) {
            return false;
        }
        if (status != OfferStatus.PENDING) {
            throw new OfferNotActiveException("This offer is no longer available", status == OfferStatus.EXPIRED);
        }
        if (!now.isBefore(expiresAt)) {
            throw new OfferNotActiveException("This offer has expired", true); // late, even if the expiry job has not run yet
        }
        status = OfferStatus.ACCEPTED;
        respondedAt = now;
        return true;
    }

    /** Declining is always allowed while pending; repeating it is a no-op. */
    public boolean reject(Instant now) {
        if (status == OfferStatus.REJECTED) {
            return false;
        }
        if (status != OfferStatus.PENDING) {
            throw new OfferNotActiveException("This offer is no longer available", status == OfferStatus.EXPIRED);
        }
        status = OfferStatus.REJECTED;
        respondedAt = now;
        return true;
    }

    /** Called by the expiry sweep. A no-op unless the offer is pending and past its deadline. */
    public boolean expire(Instant now) {
        if (status != OfferStatus.PENDING || now.isBefore(expiresAt)) {
            return false;
        }
        status = OfferStatus.EXPIRED;
        respondedAt = now;
        return true;
    }

    /** The rider cancelled while the offer was pending. */
    public boolean cancel(Instant now) {
        if (status != OfferStatus.PENDING) {
            return false;
        }
        status = OfferStatus.CANCELLED;
        respondedAt = now;
        return true;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getDriverId() { return driverId; }
    public OfferStatus getStatus() { return status; }
    public BigDecimal getPickupDistanceKm() { return pickupDistanceKm; }
    public Instant getOfferedAt() { return offeredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRespondedAt() { return respondedAt; }
}
