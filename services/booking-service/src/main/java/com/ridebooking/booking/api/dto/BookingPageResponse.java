package com.ridebooking.booking.api.dto;

import java.util.List;

/** {@code nextCursor} is null on the last page; pass it back as ?cursor= to get the following page. */
public record BookingPageResponse(List<BookingResponse> items, String nextCursor) {}
