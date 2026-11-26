package com.ridebooking.booking.domain;

public enum OfferStatus {
    /** Waiting for the driver's answer (and holding that driver exclusively). */
    PENDING,
    ACCEPTED,
    REJECTED,
    /** The driver did not answer in time. */
    EXPIRED,
    /** The rider cancelled while the offer was pending. */
    CANCELLED
}
