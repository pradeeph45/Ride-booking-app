package com.ridebooking.booking.domain;

import jakarta.persistence.Embeddable;

/** A point on Earth with an optional human-readable label. Invalid coordinates cannot exist. */
@Embeddable
public record Place(double lat, double lng, String address) {

    public Place {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90");
        }
        if (!Double.isFinite(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180");
        }
        if (address != null && address.length() > 255) {
            address = address.substring(0, 255);
        }
    }

    public static Place of(double lat, double lng, String address) {
        return new Place(lat, lng, address);
    }
}
