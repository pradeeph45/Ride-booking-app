package com.ridebooking.booking.domain;

/** The caller is not the rider or driver this action belongs to. */
public class BookingAccessDeniedException extends RuntimeException {

    public BookingAccessDeniedException(String message) {
        super(message);
    }
}
