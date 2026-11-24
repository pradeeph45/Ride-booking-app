package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.Booking;
import java.util.List;

/** One page of history plus the cursor for the next page (null on the last page). */
public record BookingPage(List<Booking> items, String nextCursor) {}
