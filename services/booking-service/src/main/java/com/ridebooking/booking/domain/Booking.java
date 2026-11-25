package com.ridebooking.booking.domain;

import com.ridebooking.booking.application.FareQuote;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for one ride request. All state changes go through the methods below, which enforce
 * the {@link BookingStatus} rules and who may perform each step. {@code @Version} makes concurrent
 * changes safe: if two drivers accept at once, the database lets exactly one commit succeed.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "rider_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID riderId;

    @Column(name = "driver_id", columnDefinition = "BINARY(16)")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, updatable = false, length = 20)
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "lat", column = @Column(name = "pickup_lat", nullable = false, updatable = false)),
            @AttributeOverride(name = "lng", column = @Column(name = "pickup_lng", nullable = false, updatable = false)),
            @AttributeOverride(name = "address", column = @Column(name = "pickup_address", updatable = false))})
    private Place pickup;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "lat", column = @Column(name = "drop_lat", nullable = false, updatable = false)),
            @AttributeOverride(name = "lng", column = @Column(name = "drop_lng", nullable = false, updatable = false)),
            @AttributeOverride(name = "address", column = @Column(name = "drop_address", updatable = false))})
    private Place drop;

    @Column(name = "distance_km", nullable = false, updatable = false, precision = 8, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "estimated_minutes", nullable = false, updatable = false)
    private int estimatedMinutes;

    @Column(name = "estimated_fare", nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal estimatedFare;

    @Column(name = "final_fare", precision = 10, scale = 2)
    private BigDecimal finalFare;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Client-chosen key making a create request safe to retry; unique per rider. Null when the client sent none. */
    @Column(name = "idempotency_key", updatable = false, length = 64)
    private String idempotencyKey;

    /** Digest of the original request, to tell a genuine retry from a reused key with a different request. */
    @Column(name = "request_fingerprint", updatable = false, length = 64)
    private String requestFingerprint;

    @Version
    private long version;

    protected Booking() {
        // for JPA
    }

    public static Booking request(UUID riderId, ServiceType type, Place pickup, Place drop,
                                  FareQuote quote, Instant now) {
        return request(riderId, type, pickup, drop, quote, now, null, null);
    }

    public static Booking request(UUID riderId, ServiceType type, Place pickup, Place drop,
                                  FareQuote quote, Instant now, String idempotencyKey, String requestFingerprint) {
        Booking b = new Booking();
        b.idempotencyKey = idempotencyKey;
        b.requestFingerprint = requestFingerprint;
        b.id = UUID.randomUUID();
        b.riderId = riderId;
        b.serviceType = type;
        b.status = BookingStatus.REQUESTED;
        b.pickup = pickup;
        b.drop = drop;
        b.distanceKm = quote.distanceKm();
        b.estimatedMinutes = quote.estimatedMinutes();
        b.estimatedFare = quote.fare();
        b.currency = quote.currency();
        b.createdAt = now;
        b.updatedAt = now;
        return b;
    }

    /**
     * A driver takes the booking. Fails if another driver already has it, so two drivers cannot both win.
     * Retry-safe: if THIS driver already holds the booking, repeating the call changes nothing.
     *
     * @return true if the booking changed, false if the call was a repeat of something already done
     */
    public boolean assignDriver(UUID driver, Instant now) {
        if (driver.equals(riderId)) {
            throw new BookingAccessDeniedException("You cannot drive your own booking");
        }
        if (driver.equals(driverId) && status != BookingStatus.CANCELLED) {
            return false; // a retry of my own accept (even a late one, once the trip has moved on)
        }
        moveTo(BookingStatus.MATCHED, now);
        this.driverId = driver;
        return true;
    }

    public boolean driverArrived(UUID driver, Instant now) {
        requireAssignedDriver(driver);
        return moveToUnlessAlready(BookingStatus.ARRIVED, now);
    }

    public boolean start(UUID driver, Instant now) {
        requireAssignedDriver(driver);
        return moveToUnlessAlready(BookingStatus.STARTED, now);
    }

    public boolean complete(UUID driver, Instant now) {
        requireAssignedDriver(driver);
        boolean changed = moveToUnlessAlready(BookingStatus.COMPLETED, now);
        if (changed) {
            this.finalFare = estimatedFare; // metered fare replaces this once live tracking exists
        }
        return changed;
    }

    /** Retry-safe: cancelling something already cancelled is a no-op. The owner check still applies first. */
    public boolean cancelByRider(UUID rider, Instant now) {
        if (!riderId.equals(rider)) {
            throw new BookingAccessDeniedException("Not your booking");
        }
        return moveToUnlessAlready(BookingStatus.CANCELLED, now);
    }

    private void requireAssignedDriver(UUID driver) {
        if (driverId == null || !driverId.equals(driver)) {
            throw new BookingAccessDeniedException("You are not the driver of this booking");
        }
    }

    /** Moves to {@code next}; if already there, does nothing (a retry). Returns whether anything changed. */
    private boolean moveToUnlessAlready(BookingStatus next, Instant now) {
        if (status == next) {
            return false;
        }
        moveTo(next, now);
        return true;
    }

    private void moveTo(BookingStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new InvalidBookingTransitionException(status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public boolean involves(UUID userId) {
        return riderId.equals(userId) || userId.equals(driverId);
    }

    public UUID getId() { return id; }
    public UUID getRiderId() { return riderId; }
    public UUID getDriverId() { return driverId; }
    public ServiceType getServiceType() { return serviceType; }
    public BookingStatus getStatus() { return status; }
    public Place getPickup() { return pickup; }
    public Place getDrop() { return drop; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public int getEstimatedMinutes() { return estimatedMinutes; }
    public BigDecimal getEstimatedFare() { return estimatedFare; }
    public BigDecimal getFinalFare() { return finalFare; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
}
