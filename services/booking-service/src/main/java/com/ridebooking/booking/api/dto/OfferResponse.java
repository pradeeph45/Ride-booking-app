package com.ridebooking.booking.api.dto;

import com.ridebooking.booking.application.matching.OfferView;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What a driver sees about an offer. The rider is never identified. */
public record OfferResponse(
        UUID id, UUID bookingId, Instant expiresAt, BigDecimal pickupDistanceKm, ServiceType serviceType,
        PlaceDto pickup, PlaceDto drop, BigDecimal distanceKm, int estimatedMinutes, BigDecimal estimatedFare, String currency) {

    public static OfferResponse from(OfferView v) {
        var o = v.offer();
        var b = v.booking();
        return new OfferResponse(o.getId(), b.getId(), o.getExpiresAt(), o.getPickupDistanceKm(), b.getServiceType(),
                PlaceDto.from(b.getPickup()), PlaceDto.from(b.getDrop()), b.getDistanceKm(), b.getEstimatedMinutes(),
                b.getEstimatedFare(), b.getCurrency());
    }
}
