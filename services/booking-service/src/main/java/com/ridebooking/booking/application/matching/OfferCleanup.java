package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.RideOfferRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Called when a rider cancels: any live offer for that ride is withdrawn and the driver is freed.
 * It locks the offer BEFORE the caller touches the booking, keeping the same lock order (offer, then booking)
 * as accept and expiry, which is what prevents deadlocks between those three.
 */
@Component
public class OfferCleanup {

    private final RideOfferRepository offers;
    private final DriverReservations reservations;
    private final Clock clock;

    public OfferCleanup(RideOfferRepository offers, DriverReservations reservations, Clock clock) {
        this.offers = offers;
        this.reservations = reservations;
        this.clock = clock;
    }

    /** Must run inside the caller's transaction. */
    public void cancelPendingFor(UUID bookingId) {
        for (RideOffer offer : offers.lockPendingForBooking(bookingId)) {
            if (offer.cancel(clock.instant())) {
                offers.save(offer);
                AfterTransaction.onCommit(() -> reservations.release(offer.getDriverId(), offer.getBookingId()));
            }
        }
    }
}
