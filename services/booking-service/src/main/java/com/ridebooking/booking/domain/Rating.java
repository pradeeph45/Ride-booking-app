package com.ridebooking.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One person's stars (and optional words) for the other person on a completed trip. Never edited afterwards. */
@Entity
@Table(name = "ratings")
public class Rating {

    public static final int MAX_COMMENT = 500;

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID bookingId;

    @Column(name = "rater_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID raterId;

    @Column(name = "ratee_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID rateeId;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(length = MAX_COMMENT, updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Rating() {
        // for JPA
    }

    public static Rating of(UUID bookingId, UUID raterId, UUID rateeId, int score, String comment, Instant now) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("Score must be between 1 and 5");
        }
        if (raterId.equals(rateeId)) {
            throw new IllegalArgumentException("You cannot rate yourself");
        }
        Rating r = new Rating();
        r.id = UUID.randomUUID();
        r.bookingId = bookingId;
        r.raterId = raterId;
        r.rateeId = rateeId;
        r.score = score;
        r.comment = normalise(comment);
        r.createdAt = now;
        return r;
    }

    /** Surrounding whitespace dropped; empty becomes "no comment"; too long is refused rather than cut. */
    public static String normalise(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("Comment must be at most " + MAX_COMMENT + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when a repeat submission says exactly what this rating already says (a retry, not a change of mind). */
    public boolean saysTheSameAs(int otherScore, String otherComment) {
        return score == otherScore && Objects.equals(comment, normalise(otherComment));
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getRaterId() { return raterId; }
    public UUID getRateeId() { return rateeId; }
    public int getScore() { return score; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
}
