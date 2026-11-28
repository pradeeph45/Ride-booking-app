package com.ridebooking.booking.domain;

/** This person already rated this trip, with something different. A rating is final. */
public class AlreadyRatedException extends RuntimeException {

    public AlreadyRatedException() {
        super("You have already rated this trip");
    }
}
