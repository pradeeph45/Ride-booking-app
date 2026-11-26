package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.RideOffer;

/** An offer together with the ride it is for, which is what a driver needs to decide. */
public record OfferView(RideOffer offer, Booking booking) {}
