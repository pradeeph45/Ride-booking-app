package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.domain.BookingStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The booking lifecycle, validation and security rules over HTTP. */
class BookingFlowIT extends BookingIntegrationTest {

    final UUID alice = UUID.randomUUID();   // rider
    final UUID bob = UUID.randomUUID();     // another rider
    final UUID dave = UUID.randomUUID();    // driver
    final UUID erin = UUID.randomUUID();    // another driver

    // ---- pricing -------------------------------------------------------------------------------

    @Test
    void estimateGivesAQuoteForEveryServiceTypeCheapestFirst() throws Exception {
        String body = mvc.perform(post("/bookings/estimate").with(rider(alice)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickup":{"lat":12.9756,"lng":77.6050},"drop":{"lat":12.9352,"lng":77.6245}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].serviceType").value("BIKE"))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andReturn().getResponse().getContentAsString();

        List<Double> fares = JsonPath.read(body, "$[*].fare");
        assertThat(fares).isSorted();
    }

    @Test
    void theServerDecidesPriceStatusAndOwnerWhateverTheClientSends() throws Exception {
        String hostile = """
                {"serviceType":"CAR_MINI","status":"COMPLETED","estimatedFare":1,"finalFare":1,"riderId":"%s","driverId":"%s",
                 "pickup":{"lat":12.9756,"lng":77.6050},"drop":{"lat":12.9352,"lng":77.6245}}""".formatted(bob, dave);

        String body = mvc.perform(post("/bookings").with(rider(alice)).contentType(MediaType.APPLICATION_JSON).content(hostile))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.driverId").doesNotExist())
                .andExpect(jsonPath("$.riderId").doesNotExist()) // never exposed
                .andReturn().getResponse().getContentAsString();

        assertThat(((Number) JsonPath.read(body, "$.estimatedFare")).doubleValue()).isGreaterThan(80.0); // not the client's 1
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(bookings.findById(id)).get().satisfies(b -> assertThat(b.getRiderId()).isEqualTo(alice)); // from the token
    }

    // ---- validation ----------------------------------------------------------------------------

    @Test
    void rejectsBadInput() throws Exception {
        String badLat = """
                {"serviceType":"BIKE","pickup":{"lat":200,"lng":77.6},"drop":{"lat":12.9,"lng":77.6}}""";
        String same = """
                {"serviceType":"BIKE","pickup":{"lat":12.9756,"lng":77.605},"drop":{"lat":12.9756,"lng":77.605}}""";
        String noType = """
                {"pickup":{"lat":12.9756,"lng":77.605},"drop":{"lat":12.9352,"lng":77.6245}}""";
        String unknownType = """
                {"serviceType":"HELICOPTER","pickup":{"lat":12.9756,"lng":77.605},"drop":{"lat":12.9352,"lng":77.6245}}""";

        for (String json : List.of(badLat, same, noType, unknownType)) {
            mvc.perform(post("/bookings").with(rider(alice)).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        assertThat(bookings.count()).isZero(); // nothing half-created
    }

    // ---- who may do what -----------------------------------------------------------------------

    @Test
    void requestsWithoutATokenAreRejected() throws Exception {
        mvc.perform(get("/bookings").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        UUID id = bookRide(alice);

        // a driver cannot book, a rider cannot drive
        mvc.perform(post("/bookings").with(driver(dave)).contentType(MediaType.APPLICATION_JSON)
                .content(MG_ROAD_TO_KORAMANGALA.formatted("BIKE"))).andExpect(status().isForbidden());
        as(rider(alice), "POST", "/bookings/" + id + "/accept").andExpect(status().isForbidden());
        as(rider(alice), "GET", "/bookings/open").andExpect(status().isForbidden());
        as(driver(dave), "POST", "/bookings/" + id + "/cancel").andExpect(status().isForbidden());
    }

    @Test
    void ridersCannotSeeOrCancelEachOthersBookings() throws Exception {
        UUID id = bookRide(alice);

        as(rider(bob), "GET", "/bookings/" + id).andExpect(status().isNotFound());
        as(rider(bob), "POST", "/bookings/" + id + "/cancel").andExpect(status().isNotFound());
        as(rider(alice), "GET", "/bookings/" + id).andExpect(status().isOk());
        // the same 404 as a booking that never existed: ids cannot be probed
        as(rider(bob), "GET", "/bookings/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Test
    void aTripGoesFromRequestedToCompleted() throws Exception {
        UUID id = bookRide(alice);

        as(driver(dave), "POST", "/bookings/" + id + "/accept")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.driverId").value(dave.toString()));
        as(driver(dave), "POST", "/bookings/" + id + "/arrive").andExpect(jsonPath("$.status").value("ARRIVED"));
        as(driver(dave), "POST", "/bookings/" + id + "/start").andExpect(jsonPath("$.status").value("STARTED"));
        as(driver(dave), "POST", "/bookings/" + id + "/complete")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalFare").isNotEmpty());

        // both parties can read it, strangers cannot
        as(rider(alice), "GET", "/bookings/" + id).andExpect(jsonPath("$.status").value("COMPLETED"));
        as(driver(dave), "GET", "/bookings/" + id).andExpect(status().isOk());
        as(driver(erin), "GET", "/bookings/" + id).andExpect(status().isNotFound());

        // the audit trail has one row per step: REQUESTED, MATCHED, ARRIVED, STARTED, COMPLETED
        assertThat(history.findByBookingIdOrderByChangedAtAsc(id)).extracting("toStatus")
                .containsExactly(BookingStatus.REQUESTED, BookingStatus.MATCHED, BookingStatus.ARRIVED,
                        BookingStatus.STARTED, BookingStatus.COMPLETED);
    }

    @Test
    void stepsCannotBeSkippedAndOnlyTheAssignedDriverMayProgress() throws Exception {
        UUID id = bookRide(alice);
        as(driver(dave), "POST", "/bookings/" + id + "/accept").andExpect(status().isOk());

        as(driver(dave), "POST", "/bookings/" + id + "/start").andExpect(status().isConflict());     // must arrive first
        as(driver(dave), "POST", "/bookings/" + id + "/complete").andExpect(status().isConflict());
        as(driver(erin), "POST", "/bookings/" + id + "/arrive").andExpect(status().isNotFound());    // not erin's trip
        as(driver(erin), "POST", "/bookings/" + id + "/accept").andExpect(status().isConflict());    // already taken
    }

    @Test
    void aRiderCanCancelBeforeTheTripStartsButNotAfter() throws Exception {
        UUID early = bookRide(alice);
        as(rider(alice), "POST", "/bookings/" + early + "/cancel")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        as(driver(dave), "POST", "/bookings/" + early + "/accept").andExpect(status().isConflict()); // cancelled: not takeable
        // a retry of the same cancel (e.g. the first response was lost) succeeds without changing anything
        as(rider(alice), "POST", "/bookings/" + early + "/cancel")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(history.findByBookingIdOrderByChangedAtAsc(early)).hasSize(2); // REQUESTED, CANCELLED: no duplicate row

        UUID late = bookRide(alice);
        as(driver(dave), "POST", "/bookings/" + late + "/accept");
        as(driver(dave), "POST", "/bookings/" + late + "/arrive");
        as(driver(dave), "POST", "/bookings/" + late + "/start");
        as(rider(alice), "POST", "/bookings/" + late + "/cancel").andExpect(status().isConflict());   // mid-trip
    }

    @Test
    void driversSeeOnlyOpenRequestsOldestFirst() throws Exception {
        UUID first = bookRide(alice);
        UUID second = bookRide(bob);
        UUID cancelled = bookRide(alice);
        UUID taken = bookRide(bob);
        as(rider(alice), "POST", "/bookings/" + cancelled + "/cancel");
        as(driver(erin), "POST", "/bookings/" + taken + "/accept");

        String body = as(driver(dave), "GET", "/bookings/open").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids).containsExactly(first.toString(), second.toString());
    }

    // ---- ride history --------------------------------------------------------------------------

    @Test
    void historyIsNewestFirstScopedToTheCallerAndPagedWithoutGapsOrRepeats() throws Exception {
        List<UUID> mine = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(bookRide(alice));
            bookRide(bob); // someone else's: must never show up
        }

        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/bookings?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            String body = as(rider(alice), "GET", url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].id"));
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3); // 2 + 2 + 1
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine.stream().map(UUID::toString).toList());
        // newest first: the last booking created comes first
        assertThat(seen.get(0)).isEqualTo(mine.get(4).toString());
        assertThat(seen.get(4)).isEqualTo(mine.get(0).toString());
    }

    @Test
    void historyCanBeFilteredByStatusAndDriversSeeTheirOwnTrips() throws Exception {
        UUID done = bookRide(alice);
        UUID open = bookRide(alice);
        as(driver(dave), "POST", "/bookings/" + done + "/accept");
        as(driver(dave), "POST", "/bookings/" + done + "/arrive");
        as(driver(dave), "POST", "/bookings/" + done + "/start");
        as(driver(dave), "POST", "/bookings/" + done + "/complete");

        as(rider(alice), "GET", "/bookings?status=COMPLETED").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(done.toString()));
        as(rider(alice), "GET", "/bookings?status=REQUESTED").andExpect(jsonPath("$.items[0].id").value(open.toString()));

        // dave's history is his driven trips; erin has none
        as(driver(dave), "GET", "/bookings").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(done.toString()));
        as(driver(erin), "GET", "/bookings").andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aTamperedOrUnknownCursorOrStatusIsABadRequest() throws Exception {
        as(rider(alice), "GET", "/bookings?cursor=not-a-real-cursor").andExpect(status().isBadRequest());
        as(rider(alice), "GET", "/bookings?status=FLYING").andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        bookRide(alice);

        as(rider(alice), "GET", "/bookings?limit=100000").andExpect(status().isOk());
        as(rider(alice), "GET", "/bookings?limit=-5").andExpect(status().isOk()); // clamped to 1, not an error
    }
}
