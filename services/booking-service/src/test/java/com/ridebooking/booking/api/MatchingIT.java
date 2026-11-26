package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.application.matching.MatchingService;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.OfferStatus;
import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.ServiceType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Matching end to end: a ride is offered to the nearest free driver, who accepts, declines or lets it lapse.
 * The pickup is MG Road (12.9756, 77.6050); the fake driver directory puts drivers at known distances from it.
 */
class MatchingIT extends BookingIntegrationTest {

    @Autowired MatchingService matching;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    // drivers at increasing distance from the pickup: ~60 m, ~600 m, ~2 km
    final UUID d1 = UUID.randomUUID();
    final UUID d2 = UUID.randomUUID();
    final UUID d3 = UUID.randomUUID();

    private void placeThreeCars() {
        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);
        directory.place(d2, ServiceType.CAR_MINI, 12.9800, 77.6100);
        directory.place(d3, ServiceType.CAR_MINI, 12.9900, 77.6200);
    }

    private List<RideOffer> offersFor(UUID ride) {
        return offers.findByBookingId(ride);
    }

    private RideOffer livingOfferFor(UUID ride) {
        List<RideOffer> pending = offersFor(ride).stream().filter(o -> o.getStatus() == OfferStatus.PENDING).toList();
        assertThat(pending).as("live offers for the ride").hasSize(1);
        return pending.get(0);
    }

    private boolean reserved(UUID driver) {
        return Boolean.TRUE.equals(redis.hasKey("driver:" + driver + ":reserved"));
    }

    // ---- the happy path ------------------------------------------------------------------------

    @Test
    void aRideIsOfferedToTheNearestFreeDriverOfTheRightType() throws Exception {
        placeThreeCars();
        directory.place(UUID.randomUUID(), ServiceType.BIKE, 12.97561, 77.60501);        // closest of all, but a bike
        UUID ride = bookRide(alice);

        assertThat(matching.tick()).isEqualTo(1);

        RideOffer offer = livingOfferFor(ride);
        assertThat(offer.getDriverId()).isEqualTo(d1);                                    // nearest car, not the nearer bike
        assertThat(offer.getExpiresAt()).isEqualTo(offer.getOfferedAt().plusSeconds(15));
        assertThat(offer.getPickupDistanceKm().doubleValue()).isBetween(0.01, 0.2);
        assertThat(reserved(d1)).isTrue();                                                 // held while the offer is live
        assertThat(bookings.findById(ride).orElseThrow().getStatus()).isEqualTo(BookingStatus.REQUESTED);

        as(driver(d1), "GET", "/offers/mine").andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(ride.toString()));
        as(driver(d2), "GET", "/offers/mine").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void whileAnOfferIsLiveTheRideIsNotOfferedToAnyoneElse() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);

        matching.tick();
        matching.tick();
        matching.tick();

        assertThat(offersFor(ride)).hasSize(1);
        assertThat(pendingOffers()).isEqualTo(1);
    }

    @Test
    void acceptingTheOfferAssignsTheDriverAndEndsMatching() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer offer = livingOfferFor(ride);

        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(d1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.driverId").value(d1.toString()));

        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(reserved(d1)).isFalse();                                                // the hold is released after commit
        assertThat(matching.tick()).isZero();                                              // nothing left to match
        assertThat(offersFor(ride)).hasSize(1);
    }

    // ---- declining and timing out --------------------------------------------------------------

    @Test
    void aDeclinedRideMovesToTheNextNearestDriverAndNeverBackToTheSame() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);

        for (UUID expected : List.of(d1, d2, d3)) {
            matching.tick();
            RideOffer offer = livingOfferFor(ride);
            assertThat(offer.getDriverId()).isEqualTo(expected);
            mvc.perform(post("/offers/" + offer.getId() + "/reject").with(driver(expected))).andExpect(status().isNoContent());
            assertThat(reserved(expected)).isFalse();                                      // freed for other rides straight away
        }

        assertThat(matching.tick()).isZero();                                              // everyone nearby said no
        assertThat(offersFor(ride)).hasSize(3).allMatch(o -> o.getStatus() == OfferStatus.REJECTED);
        assertThat(bookings.findById(ride).orElseThrow().getStatus()).isEqualTo(BookingStatus.REQUESTED); // still looking
    }

    @Test
    void whenNobodyIsAvailableTheSearchBacksOffThenFindsNewDriversLater() throws Exception {
        UUID ride = bookRide(alice);

        assertThat(matching.tick()).isZero();
        Booking waiting = bookings.findById(ride).orElseThrow();
        assertThat(waiting.getDispatchAfter()).isAfter(clock.instant());                  // will not hammer driver-service

        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);                       // a driver comes online
        assertThat(matching.tick()).isZero();                                              // too soon: still backing off
        clock.advance(Duration.ofSeconds(6));
        assertThat(matching.tick()).isEqualTo(1);
        assertThat(livingOfferFor(ride).getDriverId()).isEqualTo(d1);
    }

    @Test
    void anOfferNobodyAnswersExpiresAndGoesToTheNextDriver() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer first = livingOfferFor(ride);

        clock.advance(Duration.ofSeconds(16));                                             // past the 15 s deadline

        mvc.perform(post("/offers/" + first.getId() + "/accept").with(driver(d1)))         // too late, even before the sweep runs
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertThat(bookings.findById(ride).orElseThrow().getStatus()).isEqualTo(BookingStatus.REQUESTED);

        assertThat(matching.tick()).isEqualTo(1);                                          // expires the old offer, makes the new one
        assertThat(offers.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(livingOfferFor(ride).getDriverId()).isEqualTo(d2);
        assertThat(reserved(d1)).isFalse();
        assertThat(reserved(d2)).isTrue();
    }

    // ---- who is eligible -----------------------------------------------------------------------

    @Test
    void aDriverOnATripIsNotOfferedAnotherRide() throws Exception {
        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);
        UUID first = bookRide(alice);
        matching.tick();
        mvc.perform(post("/offers/" + livingOfferFor(first).getId() + "/accept").with(driver(d1))).andExpect(status().isOk());

        UUID second = bookRide(bob);                                                       // d1 is still on the map, but busy
        assertThat(matching.tick()).isZero();

        assertThat(offersFor(second)).isEmpty();
    }

    @Test
    void aDriverIsNeverHeldByTwoOffersAtOnce() throws Exception {
        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);                       // one driver, two waiting rides
        UUID rideA = bookRide(alice);
        UUID rideB = bookRide(bob);

        matching.tick();

        assertThat(pendingOffers()).isEqualTo(1);                                          // the driver got exactly one of them
        assertThat(offers.countPendingForDrivers(Set.of(d1))).isEqualTo(1);
        assertThat(offersFor(rideA).size() + offersFor(rideB).size()).isEqualTo(1);
    }

    @Test
    void driversBeyondTheSearchRadiusAreNotOffered() throws Exception {
        directory.place(d1, ServiceType.CAR_MINI, 13.30, 77.60);                           // ~37 km away
        UUID ride = bookRide(alice);

        assertThat(matching.tick()).isZero();

        assertThat(offersFor(ride)).isEmpty();
    }

    // ---- cancellation and ownership ------------------------------------------------------------

    @Test
    void cancellingWithdrawsTheLiveOfferAndFreesTheDriverForOtherRides() throws Exception {
        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer offer = livingOfferFor(ride);

        as(rider(alice), "POST", "/bookings/" + ride + "/cancel").andExpect(status().isOk());

        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.CANCELLED);
        assertThat(reserved(d1)).isFalse();
        as(driver(d1), "GET", "/offers/mine").andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(d1))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_ACTIVE"));

        UUID next = bookRide(bob);                                                         // the freed driver can take a new ride at once
        assertThat(matching.tick()).isEqualTo(1);
        assertThat(livingOfferFor(next).getDriverId()).isEqualTo(d1);
    }

    @Test
    void anOfferCanOnlyBeAnsweredByTheDriverItWasMadeTo() throws Exception {
        directory.place(d1, ServiceType.CAR_MINI, 12.9760, 77.6055);
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer offer = livingOfferFor(ride);

        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(d2))).andExpect(status().isNotFound());
        mvc.perform(post("/offers/" + offer.getId() + "/reject").with(driver(d2))).andExpect(status().isNotFound());
        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(rider(alice))).andExpect(status().isForbidden());

        assertThat(offers.findById(offer.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    void answeringTwiceIsSafe() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);
        matching.tick();
        RideOffer offer = livingOfferFor(ride);

        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(d1))).andExpect(status().isOk());
        mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(d1))).andExpect(status().isOk());   // lost response, retried

        UUID other = bookRide(bob);
        matching.tick();
        RideOffer second = livingOfferFor(other);
        mvc.perform(post("/offers/" + second.getId() + "/reject").with(driver(second.getDriverId()))).andExpect(status().isNoContent());
        mvc.perform(post("/offers/" + second.getId() + "/reject").with(driver(second.getDriverId()))).andExpect(status().isNoContent());
    }

    // ---- resilience ----------------------------------------------------------------------------

    @Test
    void aDriverServiceOutageSkipsTheRoundWithoutBreakingMatching() throws Exception {
        placeThreeCars();
        UUID ride = bookRide(alice);
        directory.setDown(true);

        assertThat(matching.tick()).isZero();                                              // no exception escapes
        assertThat(offersFor(ride)).isEmpty();

        directory.setDown(false);
        clock.advance(Duration.ofSeconds(6));
        assertThat(matching.tick()).isEqualTo(1);                                          // it recovers by itself
    }

    // ---- concurrency ---------------------------------------------------------------------------

    /**
     * Several service instances run the dispatcher at the same moment over many rides and few drivers. However the
     * threads interleave, no ride has two live offers, no driver holds two, and no driver is wasted.
     */
    @Test
    void manyDispatchersRacingNeverDoubleOffer() throws Exception {
        List<UUID> drivers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID d = UUID.randomUUID();
            drivers.add(d);
            directory.place(d, ServiceType.CAR_MINI, 12.9760 + 0.001 * i, 77.6055);
        }
        List<UUID> rides = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rides.add(bookRide(UUID.randomUUID()));
        }

        for (int round = 0; round < 3; round++) {
            runTogether(IntStream.range(0, 6).<Callable<Integer>>mapToObj(i -> () -> matching.tick()).toList());
        }

        List<RideOffer> pending = offers.findAll().stream().filter(o -> o.getStatus() == OfferStatus.PENDING).toList();
        assertThat(pending).hasSize(4);                                                    // all four drivers are working, none twice
        assertThat(pending.stream().map(RideOffer::getDriverId).collect(Collectors.toSet())).containsExactlyInAnyOrderElementsOf(drivers);
        assertThat(pending.stream().map(RideOffer::getBookingId).collect(Collectors.toSet())).hasSize(4);   // four different rides
        Map<UUID, Long> perRide = offers.findAll().stream().collect(Collectors.groupingBy(RideOffer::getBookingId, Collectors.counting()));
        assertThat(perRide.values()).allMatch(n -> n <= 1);                                // nobody was asked about a ride twice
        drivers.forEach(d -> assertThat(reserved(d)).isTrue());
    }

    @Test
    void theDatabaseItselfRefusesASecondLiveOfferEvenIfRedisIsBypassed() throws Exception {
        UUID rideA = bookRide(alice);
        UUID rideB = bookRide(bob);
        offerTo(rideA, d1);

        assertThatThrownBy(() -> offerTo(rideA, d2)).isInstanceOf(DataIntegrityViolationException.class);   // second offer for the same ride
        assertThatThrownBy(() -> offerTo(rideB, d1)).isInstanceOf(DataIntegrityViolationException.class);   // second offer for the same driver
        assertThat(pendingOffers()).isEqualTo(1);
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().<Callable<T>>map(t -> () -> {
                start.await();
                return t.call();
            }).map(pool::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        }
    }
}
