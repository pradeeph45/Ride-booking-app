package com.ridebooking.booking.domain;

/** The offer can no longer be answered: it expired, was rejected, or the ride was cancelled. */
public class OfferNotActiveException extends RuntimeException {

    private final boolean expired;

    public OfferNotActiveException(String message, boolean expired) {
        super(message);
        this.expired = expired;
    }

    public boolean isExpired() {
        return expired;
    }
}
