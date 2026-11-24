package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.domain.ServiceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Deliberately has no fare, status or user fields: those are decided by the server, whatever the client sends. */
public record CreateBookingRequest(
        @NotNull ServiceType serviceType,
        @NotNull @Valid PlaceDto pickup,
        @NotNull @Valid PlaceDto drop) {}
