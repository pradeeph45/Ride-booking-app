package com.ridebooking.booking.domain;

/** How the final fare of a completed trip was decided. */
public enum FareSource {
    /** There was not enough trip data to meter it, so the fare quoted when booking stands. */
    ESTIMATE,
    /** Computed from the distance and time actually driven. */
    METERED
}
