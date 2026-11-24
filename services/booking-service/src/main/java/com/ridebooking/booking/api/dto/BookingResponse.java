package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the API shows about a booking. The rider's id is intentionally absent, so drivers browsing open
 * requests learn where to go but not who is asking.
 */
public record BookingResponse(
        UUID id, BookingStatus status, ServiceType serviceType, PlaceDto pickup, PlaceDto drop,
        BigDecimal distanceKm, int estimatedMinutes, BigDecimal estimatedFare, BigDecimal finalFare,
        String currency, UUID driverId, Instant createdAt, Instant updatedAt) {

    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getStatus(), b.getServiceType(), PlaceDto.from(b.getPickup()),
                PlaceDto.from(b.getDrop()), b.getDistanceKm(), b.getEstimatedMinutes(), b.getEstimatedFare(),
                b.getFinalFare(), b.getCurrency(), b.getDriverId(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
