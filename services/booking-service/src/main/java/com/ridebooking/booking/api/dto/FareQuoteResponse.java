package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.application.FareQuote;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;

public record FareQuoteResponse(ServiceType serviceType, BigDecimal distanceKm, int estimatedMinutes,
                                BigDecimal fare, String currency) {

    public static FareQuoteResponse from(FareQuote q) {
        return new FareQuoteResponse(q.serviceType(), q.distanceKm(), q.estimatedMinutes(), q.fare(), q.currency());
    }
}
