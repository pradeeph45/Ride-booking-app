package com.ridebooking.booking.domain;

/** Unknown id, or a booking the caller is not allowed to know about (deliberately indistinguishable). */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException() {
        super("Booking not found");
    }
}
