package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.BookingStatusChange;
import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import com.ridebooking.booking.application.FareCalculator;
import java.time.Instant;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The reason bookings carry a @Version column: many people act on the same booking at the same instant.
 * These tests fire real parallel requests at a real MySQL and check that nobody ever wins twice.
 */
class BookingConcurrencyIT extends BookingIntegrationTest {

    @Autowired FareCalculator fares;

    final UUID alice = UUID.randomUUID();

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().<Callable<T>>map(t -> () -> {
                start.await(); // everyone leaves the starting line together
                return t.call();
            }).map(pool::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        }
    }

    @Test
    void fifteenDriversAcceptingTheSameBookingProduceExactlyOneWinner() throws Exception {
        UUID id = bookRide(alice);
        List<UUID> drivers = IntStream.range(0, 15).mapToObj(i -> UUID.randomUUID()).toList();

        List<Integer> statuses = runTogether(drivers.stream().<Callable<Integer>>map(d -> () ->
                as(driver(d), "POST", "/bookings/" + id + "/accept").andReturn().getResponse().getStatus()).toList());

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(14); // everyone else is told, cleanly
        assertThat(statuses).containsOnly(200, 409);                      // never a 500

        Booking booking = bookings.findById(id).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.MATCHED);
        assertThat(drivers).contains(booking.getDriverId());               // the winner is one of them, exactly one
        assertThat(history.findByBookingIdOrderByChangedAtAsc(id)).extracting(BookingStatusChange::getToStatus)
                .containsExactly(BookingStatus.REQUESTED, BookingStatus.MATCHED); // one MATCHED row, not fifteen
    }

    /**
     * A rider cancels while a driver accepts. Valid outcomes: cancel first (driver gets 409), accept first
     * then cancel (both succeed, trip ends cancelled), or a true collision where one commit loses (409).
     * What must NEVER happen is an inconsistent mix, e.g. both told "success" but the booking left MATCHED.
     */
    @RepeatedTest(8)
    void cancelRacingAcceptNeverLeavesAnInconsistentBooking() throws Exception {
        UUID id = bookRide(alice);
        UUID dave = UUID.randomUUID();

        List<Integer> results = runTogether(List.of(
                () -> as(rider(alice), "POST", "/bookings/" + id + "/cancel").andReturn().getResponse().getStatus(),
                () -> as(driver(dave), "POST", "/bookings/" + id + "/accept").andReturn().getResponse().getStatus()));
        int cancelStatus = results.get(0);
        int acceptStatus = results.get(1);

        assertThat(List.of(cancelStatus, acceptStatus)).containsOnly(200, 409);
        Booking booking = bookings.findById(id).orElseThrow();
        BookingStatus last = history.findByBookingIdOrderByChangedAtAsc(id).stream()
                .map(BookingStatusChange::getToStatus).reduce((a, b) -> b).orElseThrow();

        assertThat(last).isEqualTo(booking.getStatus());                         // audit trail agrees with the booking
        assertThat(cancelStatus == 200).isEqualTo(booking.getStatus() == BookingStatus.CANCELLED); // rider told "yes" iff cancelled
        if (booking.getStatus() == BookingStatus.MATCHED) {
            assertThat(acceptStatus).isEqualTo(200);                             // MATCHED means the driver's accept won
            assertThat(booking.getDriverId()).isEqualTo(dave);
        }
        if (acceptStatus == 409) {
            assertThat(booking.getDriverId()).isNull();                          // a refused driver must not be recorded
        }
    }

    @Test
    void manyRidersBookingAtOnceAllSucceedIndependently() throws Exception {
        List<UUID> riders = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();

        List<Integer> statuses = runTogether(riders.stream().<Callable<Integer>>map(r -> () ->
                book(r, "AUTO").andReturn().getResponse().getStatus()).toList());

        assertThat(statuses).containsOnly(201);
        assertThat(bookings.count()).isEqualTo(20);
        assertThat(bookings.findAll().stream().map(Booking::getRiderId).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(riders);
    }

    /** Several bookings created in the same microsecond: paging by (createdAt, id) must still see each exactly once. */
    @Test
    void historyPagingSurvivesIdenticalTimestamps() throws Exception {
        Instant sameInstant = Instant.parse("2026-06-01T10:00:00.123456Z");
        Place a = Place.of(12.9756, 77.6050, "A");
        Place b = Place.of(12.9352, 77.6245, "B");
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            Booking booking = Booking.request(alice, ServiceType.BIKE, a, b, fares.estimate(ServiceType.BIKE, a, b), sameInstant);
            bookings.save(booking);
            created.add(booking.getId().toString());
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int guard = 0;
        do {
            String url = "/bookings?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            String body = as(rider(alice), "GET", url).andReturn().getResponse().getContentAsString();
            List<String> ids = com.jayway.jsonpath.JsonPath.read(body, "$.items[*].id");
            ids.forEach(id -> assertThat(seen.add(id)).as("id %s returned twice", id).isTrue());
            cursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
        } while (cursor != null && ++guard < 10);

        assertThat(seen).isEqualTo(created); // nothing skipped, nothing repeated
    }
}
