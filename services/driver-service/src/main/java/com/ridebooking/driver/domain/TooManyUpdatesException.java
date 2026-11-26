package com.ridebooking.driver.domain;

/** The client is sending location updates faster than allowed. */
public class TooManyUpdatesException extends RuntimeException {

    public TooManyUpdatesException() {
        super("Location updates are limited to one per second");
    }
}
