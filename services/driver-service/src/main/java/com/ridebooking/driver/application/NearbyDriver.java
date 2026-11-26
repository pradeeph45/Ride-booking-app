package com.ridebooking.driver.application;

import java.util.UUID;

/** A live driver found near a point. Position and distance only: no personal details leave this service. */
public record NearbyDriver(UUID driverId, double lat, double lng, double distanceKm) {}
