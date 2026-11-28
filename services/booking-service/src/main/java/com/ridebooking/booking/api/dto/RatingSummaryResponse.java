package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.application.RatingService;
import java.math.BigDecimal;

/** How others rate me. The average is null until the first rating arrives. */
public record RatingSummaryResponse(BigDecimal average, int count) {

    public static RatingSummaryResponse from(RatingService.Summary s) {
        return new RatingSummaryResponse(s.average(), s.count());
    }
}
