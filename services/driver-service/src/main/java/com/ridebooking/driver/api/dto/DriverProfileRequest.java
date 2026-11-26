package com.ridebooking.driver.api.dto;

import com.ridebooking.driver.domain.VehicleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DriverProfileRequest(
        @NotNull VehicleType vehicleType,
        @NotBlank @Size(max = 20) String plateNumber,
        @NotBlank @Size(max = 60) String vehicleModel) {}
