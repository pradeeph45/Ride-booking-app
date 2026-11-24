package com.ridebooking.booking.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record FareEstimateRequest(@NotNull @Valid PlaceDto pickup, @NotNull @Valid PlaceDto drop) {}
