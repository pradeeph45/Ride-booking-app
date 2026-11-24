package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.domain.Place;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PlaceDto(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
        @Size(max = 255) String address) {

    public Place toDomain() {
        return Place.of(lat, lng, address);
    }

    public static PlaceDto from(Place p) {
        return new PlaceDto(p.lat(), p.lng(), p.address());
    }
}
