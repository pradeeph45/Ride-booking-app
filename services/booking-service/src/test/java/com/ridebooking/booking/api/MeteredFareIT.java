package com.ridebooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.booking.BookingIntegrationTest;
import com.ridebooking.booking.application.FareCalculator;
import com.ridebooking.booking.application.metering.TripMeter;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.FareSource;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The final fare is what was actually driven when the trip meter has enough data, and the quoted estimate otherwise.
 * Pings are fed straight to the meter, exactly as driver-service would publish them, and the clock is moved by hand.
 */
class MeteredFareIT extends BookingIntegrationTest {

    @Autowired TripMeter meter;
    @Autowired FareCalculator fares;

    final UUID alice = UUID.randomUUID();
    final UUID dave = UUID.randomUUID();

    static final long T0 = 1_800_000_000_000L;
    static final double LAT0 = 12.9700, LNG = 77.6000;

    /** The driver goes north in equal hops, {@code seconds} apart. */
    private void drive(int hops, double degreesPerHop, int secondsPerHop) {
        for (int i = 0; i <= hops; i++) {
            meter.record(pingJson(dave, LAT0 + i * degreesPerHop, LNG, T0 + (long) i * secondsPerHop * 1000));
        }
    }

    private BigDecimal expectedFare(ServiceType type, BigDecimal km, int minutes, String base, String perKm, String perMin, String min) {
        return new BigDecimal(base).add(km.multiply(new BigDecimal(perKm))).add(new BigDecimal(perMin).multiply(BigDecimal.valueOf(minutes)))
                .max(new BigDecimal(min)).setScale(2, RoundingMode.HALF_UP);
    }

    private String complete(UUID ride) throws Exception {
        return mvc.perform(post("/bookings/" + ride + "/complete").with(driver(dave)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private BigDecimal num(String json, String path) {
        Object v = JsonPath.read(json, path);
        return v == null ? null : new BigDecimal(v.toString());
    }

    // ---- metered ------------------------------------------------------------------------------------

    @Test
    void aTripWithGpsDataIsPricedFromWhatWasDriven() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(10, 0.002, 10);                                       // 10 hops of ~222 m: about 2.2 km
        clock.advance(Duration.ofMinutes(11).plusSeconds(30));      // 11.5 minutes since the start

        String body = complete(ride);

        BigDecimal km = num(body, "$.actualDistanceKm");
        assertThat(km.doubleValue()).isBetween(2.15, 2.30);
        assertThat(JsonPath.<Integer>read(body, "$.actualMinutes")).isEqualTo(12);
        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("METERED");
        // CAR_MINI: 50 base + 12/km + 1.50/min, at least 80
        assertThat(num(body, "$.finalFare")).isEqualByComparingTo(expectedFare(ServiceType.CAR_MINI, km, 12, "50", "12", "1.50", "80"));
        assertThat(num(body, "$.finalFare")).isNotEqualByComparingTo(num(body, "$.estimatedFare"));
        assertThat(meter.reading(ride)).isEmpty();                  // the meter is cleared once the trip is over
    }

    @Test
    void theRiderSeesTheSameBreakdown() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(10, 0.002, 10);
        clock.advance(Duration.ofMinutes(10));
        complete(ride);

        mvc.perform(get("/bookings/" + ride).with(rider(alice))).andExpect(status().isOk())
                .andExpect(jsonPath("$.fareSource").value("METERED"))
                .andExpect(jsonPath("$.actualDistanceKm").isNumber())
                .andExpect(jsonPath("$.actualMinutes").value(10));
        Booking stored = bookings.findById(ride).orElseThrow();
        assertThat(stored.getFareSource()).isEqualTo(FareSource.METERED);
        assertThat(stored.getFinalFare()).isNotEqualByComparingTo(stored.getEstimatedFare());
    }

    // ---- falls back to the estimate -------------------------------------------------------------------

    @Test
    void aTripWithNoGpsDataKeepsTheQuotedFare() throws Exception {
        UUID ride = startedTrip(alice, dave);
        clock.advance(Duration.ofMinutes(15));

        String body = complete(ride);

        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("ESTIMATE");
        assertThat(num(body, "$.finalFare")).isEqualByComparingTo(num(body, "$.estimatedFare"));
        assertThat(num(body, "$.actualDistanceKm")).isNull();
    }

    @Test
    void aSingleFixIsNotEnoughToMeter() throws Exception {
        UUID ride = startedTrip(alice, dave);
        meter.record(pingJson(dave, LAT0, LNG, T0));                // only the anchor
        meter.record(pingJson(dave, LAT0 + 0.002, LNG, T0 + 10_000)); // one counted hop: still under two
        clock.advance(Duration.ofMinutes(5));

        String body = complete(ride);

        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("ESTIMATE");
    }

    @Test
    void positionsSentBeforeTheTripStartsAreNotMetered() throws Exception {
        UUID ride = bookRide(alice);
        accept(dave, ride).andExpect(status().isOk());              // driving to the pickup now
        drive(10, 0.002, 10);                                       // these pings belong to the approach, not the trip
        mvc.perform(post("/bookings/" + ride + "/arrive").with(driver(dave))).andExpect(status().isOk());
        mvc.perform(post("/bookings/" + ride + "/start").with(driver(dave))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));

        String body = complete(ride);

        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("ESTIMATE");
    }

    @Test
    void ifRedisLostTheMeterTheTripStillCompletesAtTheEstimate() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(10, 0.002, 10);
        redis.delete("meter:" + ride);                              // e.g. a Redis restart in the middle of the trip

        String body = complete(ride);

        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("ESTIMATE");
        assertThat(num(body, "$.finalFare")).isEqualByComparingTo(num(body, "$.estimatedFare"));
    }

    // ---- guards ---------------------------------------------------------------------------------------

    @Test
    void aGpsTeleportInTheMiddleOfTheTripDoesNotInflateTheFare() throws Exception {
        UUID ride = startedTrip(alice, dave);
        for (int i = 0; i <= 10; i++) {
            double lat = i == 5 ? LAT0 + 5.0 : LAT0 + i * 0.002;    // one fix lands 550 km away
            meter.record(pingJson(dave, lat, LNG, T0 + i * 10_000L));
        }
        clock.advance(Duration.ofMinutes(10));

        String body = complete(ride);

        // the glitch is dropped and the hop around it is measured from the last good position
        assertThat(num(body, "$.actualDistanceKm").doubleValue()).isBetween(2.15, 2.30);
        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("METERED");
    }

    @Test
    void aMuchLongerRouteIsBilledAtMostOneAndAHalfTimesTheEstimate() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(20, 0.027, 120);                                      // 20 hops of ~3 km, two minutes apart: 60 km in 40 min
        clock.advance(Duration.ofMinutes(40));

        String body = complete(ride);

        BigDecimal estimate = num(body, "$.estimatedFare");
        assertThat(num(body, "$.actualDistanceKm").doubleValue()).isBetween(55.0, 65.0);   // the truth is still recorded
        assertThat(num(body, "$.finalFare")).isEqualByComparingTo(estimate.multiply(new BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP));
        assertThat(JsonPath.<String>read(body, "$.fareSource")).isEqualTo("METERED");
    }

    // ---- retries --------------------------------------------------------------------------------------

    @Test
    void completingAgainChangesNothing() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(10, 0.002, 10);
        clock.advance(Duration.ofMinutes(10));
        String first = complete(ride);

        drive(10, 0.002, 10);                                       // late pings after the trip must not matter
        clock.advance(Duration.ofMinutes(30));
        String second = complete(ride);

        assertThat(num(second, "$.finalFare")).isEqualByComparingTo(num(first, "$.finalFare"));
        assertThat(num(second, "$.actualDistanceKm")).isEqualByComparingTo(num(first, "$.actualDistanceKm"));
        assertThat(JsonPath.<Integer>read(second, "$.actualMinutes")).isEqualTo(JsonPath.<Integer>read(first, "$.actualMinutes"));
    }

    @Test
    void startingAgainDoesNotResetTheMeter() throws Exception {
        UUID ride = startedTrip(alice, dave);
        drive(5, 0.002, 10);
        double before = meter.reading(ride).orElseThrow().meters();

        mvc.perform(post("/bookings/" + ride + "/start").with(driver(dave))).andExpect(status().isOk());   // a retry of "start"

        assertThat(meter.reading(ride).orElseThrow().meters()).isEqualTo(before);
    }
}
