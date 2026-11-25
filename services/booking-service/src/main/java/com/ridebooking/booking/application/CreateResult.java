package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.Booking;

/** The booking plus whether it already existed ({@code replayed}) because the client retried with the same key. */
public record CreateResult(Booking booking, boolean replayed) {}
