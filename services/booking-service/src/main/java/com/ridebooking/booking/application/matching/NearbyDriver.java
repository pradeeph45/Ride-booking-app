package com.ridebooking.booking.application.matching;

import java.util.UUID;

/** A live driver near a pickup, as reported by driver-service. */
public record NearbyDriver(UUID driverId, double lat, double lng, double distanceKm) {}
