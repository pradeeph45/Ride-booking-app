package com.ridebooking.booking.application;

/** The same Idempotency-Key was sent with a different request. That is a client bug, never a legitimate retry. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different request");
    }
}
