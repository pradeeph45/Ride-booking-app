package com.ridebooking.booking.application.metering;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.application.metering.TripMeterMath.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Redis side of the meter: only started trips are measured, and a ping is counted exactly once. */
class TripMeterIT extends BookingIntegrationTest {

    @Autowired TripMeter meter;

    final UUID alice = UUID.randomUUID();
    final UUID dave = UUID.randomUUID();

    static final long T0 = 1_800_000_000_000L;
    static final double LAT0 = 12.9700, LNG = 77.6000;

    private String ping(int i) {
        return pingJson(dave, LAT0 + i * 0.002, LNG, T0 + i * 10_000L);   // ~222 m north every 10 s
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().<Callable<T>>map(t -> () -> {
                start.await();
                return t.call();
            }).map(pool::submit).toList();
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        }
    }

    @Test
    void aStartedTripIsMeasuredHopByHop() throws Exception {
        UUID ride = startedTrip(alice, dave);

        assertThat(meter.record(ping(0))).contains(Verdict.ANCHORED);
        assertThat(meter.record(ping(1))).contains(Verdict.COUNTED);
        assertThat(meter.record(ping(2))).contains(Verdict.COUNTED);

        TripMeter.Reading r = meter.reading(ride).orElseThrow();
        assertThat(r.steps()).isEqualTo(2);
        assertThat(r.meters()).isBetween(430.0, 460.0);
    }

    @Test
    void theMeterRecordsWhenTheTripStarted() throws Exception {
        UUID ride = startedTrip(alice, dave);

        long started = meter.reading(ride).orElseThrow().startedAt();

        assertThat(Math.abs(started - clock.instant().toEpochMilli())).isLessThan(Duration.ofMinutes(1).toMillis());   // the app's clock, not the wall clock
    }

    @Test
    void pingsFromADriverNotOnATripAreIgnored() {
        assertThat(meter.record(ping(0))).isEmpty();
    }

    @Test
    void pingsWhileDrivingToThePickupAreIgnored() throws Exception {
        UUID ride = bookRide(alice);
        accept(dave, ride);                                             // MATCHED, not STARTED

        assertThat(meter.record(ping(0))).isEmpty();
        assertThat(meter.reading(ride)).isEmpty();
    }

    @Test
    void garbageOnTheChannelIsIgnored() throws Exception {
        startedTrip(alice, dave);

        assertThat(meter.record("not json")).isEmpty();
        assertThat(meter.record("{\"lat\":1}")).isEmpty();
    }

    @Test
    void aPingDeliveredTwiceIsCountedOnce() throws Exception {
        UUID ride = startedTrip(alice, dave);
        meter.record(ping(0));

        assertThat(meter.record(ping(1))).contains(Verdict.COUNTED);
        assertThat(meter.record(ping(1))).contains(Verdict.DUPLICATE);   // the second booking-service instance hears it too

        assertThat(meter.reading(ride).orElseThrow().steps()).isEqualTo(1);
    }

    @Test
    void aLatePingIsNeverCounted() throws Exception {
        UUID ride = startedTrip(alice, dave);
        meter.record(ping(0));
        meter.record(ping(3));
        double before = meter.reading(ride).orElseThrow().meters();

        assertThat(meter.record(ping(2))).contains(Verdict.DUPLICATE);   // older than the newest one seen

        assertThat(meter.reading(ride).orElseThrow().meters()).isEqualTo(before);
    }

    @Test
    void theSamePingFromManyThreadsAtOnceIsCountedExactlyOnce() throws Exception {
        UUID ride = startedTrip(alice, dave);
        meter.record(ping(0));

        List<Optional<Verdict>> verdicts = runTogether(java.util.stream.IntStream.range(0, 12)
                .<Callable<Optional<Verdict>>>mapToObj(i -> () -> meter.record(ping(1))).toList());

        assertThat(verdicts.stream().filter(v -> v.equals(Optional.of(Verdict.COUNTED)))).hasSize(1);
        TripMeter.Reading r = meter.reading(ride).orElseThrow();
        assertThat(r.steps()).isEqualTo(1);
        assertThat(r.meters()).isBetween(215.0, 230.0);                   // one hop, not twelve
    }

    @Test
    void pingsArrivingInAnyOrderNeverCountMoreThanTheRealPath() throws Exception {
        UUID ride = startedTrip(alice, dave);
        List<Integer> order = new ArrayList<>(java.util.stream.IntStream.rangeClosed(0, 20).boxed().toList());
        Collections.shuffle(order, new java.util.Random(7));

        runTogether(order.stream().<Callable<Optional<Verdict>>>map(i -> () -> meter.record(ping(i))).toList());

        TripMeter.Reading r = meter.reading(ride).orElseThrow();
        double truePath = TripMeterMath.distanceMeters(LAT0, LNG, LAT0 + 20 * 0.002, LNG);
        assertThat(r.meters()).isLessThanOrEqualTo(truePath + 1.0);       // late ones are dropped, none counted twice
        assertThat(r.meters()).isGreaterThan(0);
    }

    @Test
    void theRealRedisChannelFeedsTheMeter() throws Exception {
        UUID ride = startedTrip(alice, dave);

        // exactly what driver-service does: publish on the shared channel. The listener must pick it up by itself.
        for (int i = 0; i <= 3; i++) {
            redis.convertAndSend("driver-locations", ping(i));
        }

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && meter.reading(ride).orElseThrow().steps() < 3) {
            Thread.sleep(100);
        }
        assertThat(meter.reading(ride).orElseThrow().steps()).isEqualTo(3);
    }
}
