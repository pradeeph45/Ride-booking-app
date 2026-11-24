package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;

/** The price and trip estimate for one service type. Always computed by the server, never by the client. */
public record FareQuote(ServiceType serviceType, BigDecimal distanceKm, int estimatedMinutes,
                        BigDecimal fare, String currency) {}
