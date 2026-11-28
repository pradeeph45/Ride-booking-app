package com.ridebooking.booking.application.metering;

import com.ridebooking.booking.application.FareCalculator;
import com.ridebooking.booking.application.MeteredFare;
import com.ridebooking.booking.domain.Booking;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides, at completion, whether a trip can be priced from what was driven. It needs a meter with at least two
 * counted hops; anything less (no pings, Redis lost the meter, a driver whose app never reported) means the quoted
 * estimate stands, so a missing meter can never produce a nonsense fare.
 */
@Component
public class FareSettlement {

    private static final Logger log = LoggerFactory.getLogger(FareSettlement.class);
    static final int MIN_STEPS = 2;

    private final TripMeter meter;
    private final FareCalculator fares;

    public FareSettlement(TripMeter meter, FareCalculator fares) {
        this.meter = meter;
        this.fares = fares;
    }

    /** @return the metered fare, or empty when the estimate should stand */
    public Optional<MeteredFare> settle(Booking booking, Instant now) {
        try {
            return meter.reading(booking.getId())
                    .filter(r -> r.steps() >= MIN_STEPS)
                    .map(r -> fares.metered(booking.getServiceType(), booking.getEstimatedFare(), r.meters(),
                            Math.max(0, (now.toEpochMilli() - r.startedAt()) / 1000)));
        } catch (RuntimeException e) {
            log.warn("Could not read the trip meter for booking {}; completing at the estimate", booking.getId(), e);
            return Optional.empty();
        }
    }
}
