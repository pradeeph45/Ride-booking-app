package com.ridebooking.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One row of the audit trail: who moved which booking from which status to which, and when. */
@Entity
@Table(name = "booking_status_history")
public class BookingStatusChange {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private BookingStatus toStatus;

    @Column(name = "actor_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID actorId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected BookingStatusChange() {
        // for JPA
    }

    public static BookingStatusChange of(UUID bookingId, BookingStatus from, BookingStatus to, UUID actorId, Instant at) {
        BookingStatusChange c = new BookingStatusChange();
        c.id = UUID.randomUUID();
        c.bookingId = bookingId;
        c.fromStatus = from;
        c.toStatus = to;
        c.actorId = actorId;
        c.changedAt = at;
        return c;
    }

    public UUID getBookingId() { return bookingId; }
    public BookingStatus getFromStatus() { return fromStatus; }
    public BookingStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public Instant getChangedAt() { return changedAt; }
}
