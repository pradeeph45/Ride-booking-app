package com.ridebooking.booking.domain;

/**
 * What the rider wants to travel in. Declared from cheapest to dearest (tests rely on that order).
 * Parcel delivery is a separate booking kind that arrives in a later phase.
 */
public enum ServiceType {
    BIKE,
    AUTO,
    CAR_MINI,
    CAR_SEDAN,
    CAR_SUV
}
