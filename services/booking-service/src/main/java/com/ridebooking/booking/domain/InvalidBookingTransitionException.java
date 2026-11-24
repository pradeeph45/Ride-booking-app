package com.ridebooking.booking.domain;

/** The booking is not in a state where this action makes sense (already accepted, cancelled, finished...). */
public class InvalidBookingTransitionException extends RuntimeException {

    public InvalidBookingTransitionException(BookingStatus from, BookingStatus to) {
        super("Cannot move a booking from " + from + " to " + to);
    }
}
