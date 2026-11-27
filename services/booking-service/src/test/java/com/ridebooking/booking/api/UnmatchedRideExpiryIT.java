package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.application.BookingService;
import com.ridebooking.booking.application.matching.MatchingService;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.BookingStatusChange;
import com.ridebooking.booking.domain.OfferStatus;
import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.ServiceType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A ride nobody accepts within the search window is closed as EXPIRED instead of waiting forever. */
class UnmatchedRideExpiryIT extends BookingIntegrationTest {

    @Autowired MatchingService matching;

    final UUID alice = UUID.randomUUID();
    final UUID dave = UUID.randomUUID();

    private BookingStatus statusOf(UUID ride) {
        return bookings.findById(ride).orElseThrow().getStatus();
    }

    @Test
    void aRideStillWithinTheWindowKeepsWaiting() throws Exception {
        UUID ride = bookRide(alice);
        clock.advance(Duration.ofMinutes(2));

        matching.tick();

        assertThat(statusOf(ride)).isEqualTo(BookingStatus.REQUESTED);
    }

    @Test
    void aRideNobodyAcceptedIsClosedAsExpiredAndTheRiderCanSeeIt() throws Exception {
        UUID ride = bookRide(alice);
        clock.advance(Duration.ofMinutes(3).plusSeconds(1));

        matching.tick();

        assertThat(statusOf(ride)).isEqualTo(BookingStatus.EXPIRED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/bookings/" + ride).with(rider(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        List<BookingStatusChange> trail = history.findByBookingIdOrderByChangedAtAsc(ride);
        assertThat(trail).extracting(BookingStatusChange::getToStatus).containsExactly(BookingStatus.REQUESTED, BookingStatus.EXPIRED);
        assertThat(trail.get(1).getActorId()).isEqualTo(BookingService.SYSTEM);           // recorded as the system, not a user
    }

    @Test
    void expiryWithdrawsTheLiveOfferAndFreesTheDriver() throws Exception {
        directory.place(dave, ServiceType.CAR_MINI, 12.9760, 77.6055);
        UUID ride = bookRide(alice);
        matching.tick();                                                                   // dave is offered the ride
        assertThat(pendingOffers()).isEqualTo(1);
        assertThat(redis.hasKey("driver:" + dave + ":reserved")).isTrue();

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        matching.tick();

        assertThat(statusOf(ride)).isEqualTo(BookingStatus.EXPIRED);
        assertThat(pendingOffers()).isZero();
        assertThat(offers.findByBookingId(ride)).extracting(RideOffer::getStatus)
                .containsAnyOf(OfferStatus.CANCELLED, OfferStatus.EXPIRED).doesNotContain(OfferStatus.PENDING);
        assertThat(redis.hasKey("driver:" + dave + ":reserved")).isFalse();
        as(driver(dave), "GET", "/offers/mine").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anExpiredRideCannotBeAcceptedOrCancelledAndIsNeverOfferedAgain() throws Exception {
        directory.place(dave, ServiceType.CAR_MINI, 12.9760, 77.6055);
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer offer = offers.findByBookingId(ride).get(0);
        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        matching.tick();

        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(dave))).andExpect(status().isConflict());
        mvc.perform(post("/bookings/" + ride + "/cancel").with(rider(alice))).andExpect(status().isConflict());
        clock.advance(Duration.ofMinutes(1));
        matching.tick();

        assertThat(statusOf(ride)).isEqualTo(BookingStatus.EXPIRED);
        assertThat(pendingOffers()).isZero();
    }

    @Test
    void aRideThatWasAcceptedInTimeIsNeverExpired() throws Exception {
        UUID ride = bookRide(alice);
        accept(dave, ride).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));

        matching.tick();

        assertThat(statusOf(ride)).isEqualTo(BookingStatus.MATCHED);
    }

    @Test
    void expiryRacingAnAcceptGivesExactlyOneOutcomeAndNeverBoth() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID ride = bookRide(alice);
            RideOffer offer = offerTo(ride, dave);
            Instant cutoff = clock.instant().plusSeconds(1);                                // the ride is already past the window
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<Integer> accept = pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(dave))).andReturn().getResponse().getStatus();
                });
                Future<Boolean> expire = pool.submit(() -> {
                    start.await();
                    return expirer.expireIfUnmatched(ride, cutoff);
                });
                start.countDown();
                int acceptStatus = accept.get();
                boolean expired = expire.get();

                Booking result = bookings.findById(ride).orElseThrow();
                if (result.getStatus() == BookingStatus.MATCHED) {
                    assertThat(acceptStatus).isEqualTo(200);
                    assertThat(expired).isFalse();
                    assertThat(result.getDriverId()).isEqualTo(dave);
                } else {
                    assertThat(result.getStatus()).isEqualTo(BookingStatus.EXPIRED);
                    assertThat(acceptStatus).isEqualTo(409);                                // told "too late", not "yes"
                    assertThat(expired).isTrue();
                    assertThat(result.getDriverId()).isNull();
                }
            }
            // whichever won, nothing is left half-done
            clock.advance(Duration.ofMinutes(5));
            matching.tick();
            assertThat(pendingOffers()).isZero();
            offers.deleteAll();
            history.deleteAll();
            bookings.deleteAll();
        }
    }

    @Autowired BookingService expirer;
}
