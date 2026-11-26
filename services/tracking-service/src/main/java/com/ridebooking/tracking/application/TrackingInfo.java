package com.ridebooking.tracking.application;

import java.util.UUID;

/** Who is on a live trip, as registered by booking-service. */
public record TrackingInfo(UUID riderId, UUID driverId, String status) {

    /** Only the rider and the driver of the trip may watch it. */
    public boolean mayView(UUID userId) {
        return userId != null && (userId.equals(riderId) || userId.equals(driverId));
    }
}
