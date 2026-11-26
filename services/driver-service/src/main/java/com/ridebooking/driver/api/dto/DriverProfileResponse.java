package com.ridebooking.driver.api.dto;

import com.ridebooking.driver.domain.DriverProfile;
import com.ridebooking.driver.domain.DriverStatus;
import com.ridebooking.driver.domain.VehicleType;

public record DriverProfileResponse(VehicleType vehicleType, String plateNumber, String vehicleModel, DriverStatus status) {

    public static DriverProfileResponse from(DriverProfile p) {
        return new DriverProfileResponse(p.getVehicleType(), p.getPlateNumber(), p.getVehicleModel(), p.getStatus());
    }
}
