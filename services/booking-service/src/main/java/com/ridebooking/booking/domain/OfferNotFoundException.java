package com.ridebooking.booking.domain;

/** Unknown offer, or one made to a different driver (deliberately indistinguishable). */
public class OfferNotFoundException extends RuntimeException {

    public OfferNotFoundException() {
        super("Offer not found");
    }
}
