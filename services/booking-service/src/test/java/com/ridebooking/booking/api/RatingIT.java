package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.domain.Rating;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Rider and driver rate each other once per completed trip. */
class RatingIT extends BookingIntegrationTest {

    final UUID alice = UUID.randomUUID();   // rider
    final UUID dave = UUID.randomUUID();    // driver
    final UUID mallory = UUID.randomUUID(); // someone who was not on the trip

    /** Books a ride, has the driver take it and drive it to the end. */
    private UUID completedTrip(UUID rider, UUID driver) throws Exception {
        UUID ride = bookRide(rider);
        accept(driver, ride).andExpect(status().isOk());
        for (String step : List.of("arrive", "start", "complete")) {
            mvc.perform(post("/bookings/" + ride + "/" + step).with(driver(driver))).andExpect(status().isOk());
        }
        return ride;
    }

    private ResultActions rate(RequestPostProcessor who, UUID ride, int score, String comment) throws Exception {
        String body = comment == null ? "{\"score\":" + score + "}"
                : "{\"score\":" + score + ",\"comment\":\"" + comment + "\"}";
        return mvc.perform(post("/bookings/" + ride + "/rating").with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions summary(RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/ratings/me").with(who));
    }

    // ---- who may rate, and when ----------------------------------------------------------------

    @Test
    void theRiderRatesTheDriverAfterTheTrip() throws Exception {
        UUID ride = completedTrip(alice, dave);

        rate(rider(alice), ride, 5, "  Smooth and on time ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(5))
                .andExpect(jsonPath("$.comment").value("Smooth and on time"))
                .andExpect(jsonPath("$.bookingId").value(ride.toString()));

        Rating stored = ratings.findByBookingIdAndRaterId(ride, alice).orElseThrow();
        assertThat(stored.getRateeId()).isEqualTo(dave);
        summary(driver(dave)).andExpect(jsonPath("$.average").value(5.0)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void theDriverRatesTheRiderToo() throws Exception {
        UUID ride = completedTrip(alice, dave);

        rate(driver(dave), ride, 4, null).andExpect(status().isCreated());

        assertThat(ratings.findByBookingIdAndRaterId(ride, dave).orElseThrow().getRateeId()).isEqualTo(alice);
        summary(rider(alice)).andExpect(jsonPath("$.average").value(4.0)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void aTripThatIsNotFinishedCannotBeRated() throws Exception {
        UUID ride = bookRide(alice);
        accept(dave, ride).andExpect(status().isOk());                    // MATCHED, not completed

        rate(rider(alice), ride, 5, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIP_NOT_COMPLETED"));
        rate(driver(dave), ride, 5, null).andExpect(status().isConflict());
        assertThat(ratings.count()).isZero();
    }

    @Test
    void aCancelledTripCannotBeRated() throws Exception {
        UUID ride = bookRide(alice);
        mvc.perform(post("/bookings/" + ride + "/cancel").with(rider(alice))).andExpect(status().isOk());

        rate(rider(alice), ride, 1, null).andExpect(status().isConflict());
    }

    @Test
    void someoneWhoWasNotOnTheTripCannotRateOrPeek() throws Exception {
        UUID ride = completedTrip(alice, dave);

        rate(rider(mallory), ride, 1, "fake").andExpect(status().isNotFound());   // same answer as "no such trip"
        mvc.perform(get("/bookings/" + ride + "/rating").with(rider(mallory))).andExpect(status().isNotFound());
        assertThat(ratings.count()).isZero();
        assertThat(ratingStats.count()).isZero();
    }

    @Test
    void invalidInputIsRefusedBeforeAnythingIsStored() throws Exception {
        UUID ride = completedTrip(alice, dave);

        rate(rider(alice), ride, 0, null).andExpect(status().isBadRequest());
        rate(rider(alice), ride, 6, null).andExpect(status().isBadRequest());
        rate(rider(alice), ride, 5, "x".repeat(501)).andExpect(status().isBadRequest());
        mvc.perform(post("/bookings/" + ride + "/rating").with(rider(alice)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());                              // a missing score is not silently a zero-star vote
        assertThat(ratings.count()).isZero();
    }

    // ---- repeats and changes of mind -------------------------------------------------------------

    @Test
    void sendingTheSameRatingAgainIsHarmlessAndCountedOnce() throws Exception {
        UUID ride = completedTrip(alice, dave);
        rate(rider(alice), ride, 4, "Good").andExpect(status().isCreated());

        rate(rider(alice), ride, 4, " Good ").andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));

        assertThat(ratings.count()).isEqualTo(1);
        summary(driver(dave)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void aDifferentSecondRatingIsRefusedAndTheFirstStands() throws Exception {
        UUID ride = completedTrip(alice, dave);
        rate(rider(alice), ride, 5, "Great").andExpect(status().isCreated());

        rate(rider(alice), ride, 1, "Terrible").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RATED"));

        assertThat(ratings.findByBookingIdAndRaterId(ride, alice).orElseThrow().getScore()).isEqualTo(5);
        summary(driver(dave)).andExpect(jsonPath("$.average").value(5.0)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void youSeeYourOwnRatingOfATripAndNeverTheOtherPersons() throws Exception {
        UUID ride = completedTrip(alice, dave);
        mvc.perform(get("/bookings/" + ride + "/rating").with(rider(alice))).andExpect(status().isNoContent());

        rate(driver(dave), ride, 1, "Rude").andExpect(status().isCreated());
        mvc.perform(get("/bookings/" + ride + "/rating").with(rider(alice))).andExpect(status().isNoContent()); // dave's words are not alice's to read

        rate(rider(alice), ride, 5, null).andExpect(status().isCreated());
        mvc.perform(get("/bookings/" + ride + "/rating").with(rider(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(5));
        mvc.perform(get("/bookings/" + ride + "/rating").with(driver(dave)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1)).andExpect(jsonPath("$.comment").value("Rude"));
    }

    // ---- the average ---------------------------------------------------------------------------------

    @Test
    void theAverageCoversEveryTripAndRoundsToTwoDecimals() throws Exception {
        summary(driver(dave)).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.average").doesNotExist());

        for (int score : new int[] {5, 4, 4}) {
            UUID someRider = UUID.randomUUID();                                   // each trip has its own rider
            rate(rider(someRider), completedTrip(someRider, dave), score, null).andExpect(status().isCreated());
        }

        summary(driver(dave)).andExpect(jsonPath("$.count").value(3)).andExpect(jsonPath("$.average").value(4.33));
    }

    // ---- concurrency -----------------------------------------------------------------------------------

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

    @Test
    void aDoubleTapFromManyThreadsStoresOneRatingAndCountsItOnce() throws Exception {
        UUID ride = completedTrip(alice, dave);

        List<Integer> statuses = runTogether(IntStream.range(0, 12).<Callable<Integer>>mapToObj(i -> () ->
                rate(rider(alice), ride, 5, "Great").andReturn().getResponse().getStatus()).toList());

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);           // exactly one request created it
        assertThat(ratings.count()).isEqualTo(1);
        summary(driver(dave)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.average").value(5.0));
    }

    @Test
    void twoContradictoryRatingsAtOnceLeaveExactlyOne() throws Exception {
        UUID ride = completedTrip(alice, dave);

        List<Integer> statuses = runTogether(List.of(
                () -> rate(rider(alice), ride, 5, null).andReturn().getResponse().getStatus(),
                () -> rate(rider(alice), ride, 1, null).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);                 // one wins, the other is told it is final
        assertThat(ratings.count()).isEqualTo(1);
        summary(driver(dave)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void manyRidersRatingTheSameDriverAtOnceLoseNoTotals() throws Exception {
        List<UUID> trips = new ArrayList<>();
        List<UUID> riders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID rider = UUID.randomUUID();
            riders.add(rider);
            trips.add(completedTrip(rider, dave));
        }

        List<Integer> statuses = runTogether(IntStream.range(0, 10).<Callable<Integer>>mapToObj(i -> () ->
                rate(rider(riders.get(i)), trips.get(i), 1 + (i % 5), null).andReturn().getResponse().getStatus()).toList());

        assertThat(statuses).containsOnly(201);
        // scores 1..5 twice: sum 30 over 10 ratings. A lost update in the running totals would show here.
        summary(driver(dave)).andExpect(jsonPath("$.count").value(10)).andExpect(jsonPath("$.average").value(3.0));
    }
}
