package com.ridebooking.booking.application;

import java.math.BigDecimal;

/**
 * A fare worked out from the trip actually driven. {@code capped} is true when the raw figure was higher than the
 * allowed multiple of the estimate and was brought down to it.
 */
public record MeteredFare(BigDecimal distanceKm, int minutes, BigDecimal fare, boolean capped) {}
