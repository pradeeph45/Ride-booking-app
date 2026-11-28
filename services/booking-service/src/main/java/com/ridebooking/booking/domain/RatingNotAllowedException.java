package com.ridebooking.booking.domain;

/** Ratings open only once the trip is COMPLETED. */
public class RatingNotAllowedException extends RuntimeException {

    public RatingNotAllowedException() {
        super("You can rate a trip once it is completed");
    }
}
