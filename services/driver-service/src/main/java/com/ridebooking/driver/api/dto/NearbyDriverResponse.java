package com.ridebooking.driver.api.dto;

import com.ridebooking.driver.application.NearbyDriver;
import java.util.UUID;

public record NearbyDriverResponse(UUID driverId, double lat, double lng, double distanceKm) {

    public static NearbyDriverResponse from(NearbyDriver d) {
        return new NearbyDriverResponse(d.driverId(), d.lat(), d.lng(), d.distanceKm());
    }
}
