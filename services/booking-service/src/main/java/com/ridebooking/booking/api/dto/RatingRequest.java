package com.ridebooking.booking.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record RatingRequest(
        @Min(value = 1, message = "Score must be between 1 and 5") @Max(value = 5, message = "Score must be between 1 and 5") int score,
        @Size(max = 500, message = "Comment must be at most 500 characters") String comment) {
}
