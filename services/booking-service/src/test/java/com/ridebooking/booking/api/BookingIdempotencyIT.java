package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.domain.BookingStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** Retries and double-clicks must never create a second booking, and repeated actions must be harmless. */
class BookingIdempotencyIT extends BookingIntegrationTest {

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final UUID dave = UUID.randomUUID();
    final UUID erin = UUID.randomUUID();

    private ResultActions bookWithKey(UUID riderId, String key, String serviceType) throws Exception {
        var request = post("/bookings").with(rider(riderId)).contentType(MediaType.APPLICATION_JSON)
                .content(MG_ROAD_TO_KORAMANGALA.formatted(serviceType));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theSameKeyTwiceReturnsTheOriginalBookingInsteadOfCreatingAnother() throws Exception {
        ResultActions first = bookWithKey(alice, "order-0001-abcd", "CAR_MINI")
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
        String id = idOf(first);

        bookWithKey(alice, "order-0001-abcd", "CAR_MINI")
                .andExpect(status().isOk())                                    // 200, not 201: nothing was created
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        assertThat(bookings.count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);                              // and no duplicate audit row
    }

    @Test
    void aReplayShowsTheBookingAsItIsNowNotAsItWasWhenCreated() throws Exception {
        String id = idOf(bookWithKey(alice, "order-0002-abcd", "CAR_MINI"));
        as(rider(alice), "POST", "/bookings/" + id + "/cancel").andExpect(status().isOk());

        bookWithKey(alice, "order-0002-abcd", "CAR_MINI")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));           // a retry must not resurrect it

        assertThat(bookings.count()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerRiderSoTwoPeopleMayUseTheSameKey() throws Exception {
        String a = idOf(bookWithKey(alice, "shared-key-0001", "BIKE"));
        String b = idOf(bookWithKey(bob, "shared-key-0001", "BIKE"));

        assertThat(a).isNotEqualTo(b);
        assertThat(bookings.count()).isEqualTo(2);   // and one rider can never replay into another's booking
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejectedAndCreatesNothing() throws Exception {
        bookWithKey(alice, "order-0003-abcd", "BIKE").andExpect(status().isCreated());

        bookWithKey(alice, "order-0003-abcd", "CAR_SUV")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(bookings.count()).isEqualTo(1);
    }

    @Test
    void withoutAKeyEveryRequestIsANewBooking() throws Exception {
        bookWithKey(alice, null, "BIKE").andExpect(status().isCreated());
        bookWithKey(alice, null, "BIKE").andExpect(status().isCreated());

        assertThat(bookings.count()).isEqualTo(2); // the header is optional; opting in is the client's job
    }

    @Test
    void malformedKeysAreRejected() throws Exception {
        for (String bad : List.of("short", "has spaces in it!", "x".repeat(65), "semi;colon-key-1", "emoji-key-🚕🚕🚕")) {
            bookWithKey(alice, bad, "BIKE").andExpect(status().isBadRequest());
        }
        assertThat(bookings.count()).isZero();
    }

    @Test
    void twentyParallelRetriesWithOneKeyCreateExactlyOneBooking() throws Exception {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<int[]> statuses = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String[]>> futures = IntStream.range(0, threads).<Callable<String[]>>mapToObj(i -> () -> {
                start.await();
                ResultActions r = bookWithKey(alice, "double-click-0001", "CAR_MINI");
                var response = r.andReturn().getResponse();
                return new String[] {String.valueOf(response.getStatus()), response.getStatus() < 300 ? JsonPath.read(response.getContentAsString(), "$.id") : ""};
            }).map(pool::submit).toList();
            start.countDown();
            for (Future<String[]> f : futures) {
                String[] result = f.get();
                statuses.add(new int[] {Integer.parseInt(result[0])});
                ids.add(result[1]);
            }
        }

        long created = statuses.stream().filter(s -> s[0] == 201).count();
        long replayed = statuses.stream().filter(s -> s[0] == 200).count();
        assertThat(created).isEqualTo(1);                       // exactly one request actually created it
        assertThat(replayed).isEqualTo(threads - 1);            // the rest got the same booking back, not an error
        assertThat(ids).hasSize(1);                             // all twenty callers hold the same booking id
        assertThat(bookings.count()).isEqualTo(1);
        assertThat(history.count()).isEqualTo(1);
    }

    // ---- retry-safe actions --------------------------------------------------------------------

    @Test
    void aDriverRetryingAcceptGetsSuccessWhileADifferentDriverStillGetsConflict() throws Exception {
        UUID id = bookRide(alice);

        as(driver(dave), "POST", "/bookings/" + id + "/accept").andExpect(status().isOk());
        as(driver(dave), "POST", "/bookings/" + id + "/accept")             // the response was lost; dave tries again
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED"));
        as(driver(erin), "POST", "/bookings/" + id + "/accept").andExpect(status().isConflict());

        assertThat(history.findByBookingIdOrderByChangedAtAsc(id)).extracting("toStatus")
                .containsExactly(BookingStatus.REQUESTED, BookingStatus.MATCHED);
    }

    @Test
    void everyStepOfTheTripCanBeRepeatedSafely() throws Exception {
        UUID id = bookRide(alice);
        for (String step : List.of("accept", "arrive", "start", "complete")) {
            as(driver(dave), "POST", "/bookings/" + id + "/" + step).andExpect(status().isOk());
            as(driver(dave), "POST", "/bookings/" + id + "/" + step).andExpect(status().isOk()); // repeat
        }

        as(rider(alice), "GET", "/bookings/" + id).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(history.findByBookingIdOrderByChangedAtAsc(id)).hasSize(5); // one row per real change, none for repeats
    }

    @Test
    void repeatingAnActionDoesNotLetAStrangerThrough() throws Exception {
        UUID id = bookRide(alice);
        as(driver(dave), "POST", "/bookings/" + id + "/accept");
        as(driver(dave), "POST", "/bookings/" + id + "/arrive");

        as(driver(erin), "POST", "/bookings/" + id + "/arrive").andExpect(status().isNotFound());   // not erin's trip
        as(rider(bob), "POST", "/bookings/" + id + "/cancel").andExpect(status().isNotFound());      // not bob's booking
    }
}
