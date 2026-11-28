package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.domain.Rating;
import java.time.Instant;
import java.util.UUID;

/** What the rater gave. It never carries the other person's identity. */
public record RatingResponse(UUID bookingId, int score, String comment, Instant createdAt) {

    public static RatingResponse from(Rating r) {
        return new RatingResponse(r.getBookingId(), r.getScore(), r.getComment(), r.getCreatedAt());
    }
}
