package com.ridebooking.booking.application.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ridebooking.booking.application.metering.TripMeterMath.Step;
import com.ridebooking.booking.application.metering.TripMeterMath.Verdict;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules for turning noisy GPS pings into a trustworthy distance. */
class TripMeterMathTest {

    static final MeteringProperties LIMITS = new MeteringProperties(true, 200, 5, 3, Duration.ofHours(6));
    static final UUID DRIVER = UUID.randomUUID();
    static final long T0 = 1_800_000_000_000L;

    /** About 111 m per 0.001 degree of latitude. */
    static final double LAT0 = 12.9700;
    static final double LNG = 77.6000;

    MeterState state = MeterState.started(T0);

    private Verdict feed(double lat, long secondsAfterStart) {
        Step step = TripMeterMath.apply(state, new DriverPing(DRIVER, lat, LNG, T0 + secondsAfterStart * 1000), LIMITS);
        state = step.state();
        return step.verdict();
    }

    @Test
    void theFirstPingOnlyAnchorsTheMeter() {
        assertThat(feed(LAT0, 1)).isEqualTo(Verdict.ANCHORED);
        assertThat(state.meters()).isZero();
        assertThat(state.steps()).isZero();
    }

    @Test
    void ordinaryDrivingAddsUpHopByHop() {
        feed(LAT0, 1);

        assertThat(feed(LAT0 + 0.002, 11)).isEqualTo(Verdict.COUNTED);   // ~222 m in 10 s: 80 km/h
        assertThat(feed(LAT0 + 0.004, 21)).isEqualTo(Verdict.COUNTED);

        double expected = TripMeterMath.distanceMeters(LAT0, LNG, LAT0 + 0.004, LNG);
        assertThat(state.meters()).isCloseTo(expected, within(0.5));
        assertThat(state.steps()).isEqualTo(2);
    }

    @Test
    void aPingNotNewerThanTheLastIsIgnored() {
        feed(LAT0, 1);
        feed(LAT0 + 0.002, 11);
        double before = state.meters();

        assertThat(feed(LAT0 + 0.004, 11)).isEqualTo(Verdict.DUPLICATE);  // the same moment again (two instances heard it)
        assertThat(feed(LAT0 + 0.004, 5)).isEqualTo(Verdict.DUPLICATE);   // arrived late, out of order

        assertThat(state.meters()).isEqualTo(before);
    }

    @Test
    void wobbleWhileStandingStillIsNotCounted() {
        feed(LAT0, 1);

        assertThat(feed(LAT0 + 0.00002, 4)).isEqualTo(Verdict.WOBBLE);    // ~2 m
        assertThat(feed(LAT0 - 0.00002, 7)).isEqualTo(Verdict.WOBBLE);

        assertThat(state.meters()).isZero();
    }

    @Test
    void slowDriftStillAddsUpBecauseTheAnchorStaysPut() {
        feed(LAT0, 1);
        // 3 m per ping is under the threshold each time, but measured from the anchor the drift reaches 6 m on the second ping
        assertThat(feed(LAT0 + 0.000027, 4)).isEqualTo(Verdict.WOBBLE);
        assertThat(feed(LAT0 + 0.000054, 7)).isEqualTo(Verdict.COUNTED);

        assertThat(state.meters()).isBetween(5.0, 7.0);
    }

    @Test
    void anImpossibleJumpIsNotCountedAndDoesNotMoveTheAnchor() {
        feed(LAT0, 1);
        feed(LAT0 + 0.002, 11);
        double before = state.meters();

        assertThat(feed(LAT0 + 5.0, 14)).isEqualTo(Verdict.GLITCH);       // 550 km in 3 s

        assertThat(state.meters()).isEqualTo(before);
        assertThat(state.anchorLat()).isEqualTo(LAT0 + 0.002);
        // the next good ping is measured from the real last position, so no distance is lost
        assertThat(feed(LAT0 + 0.004, 21)).isEqualTo(Verdict.COUNTED);
        assertThat(state.meters()).isCloseTo(TripMeterMath.distanceMeters(LAT0, LNG, LAT0 + 0.004, LNG), within(0.5));
        assertThat(state.glitches()).isZero();
    }

    @Test
    void repeatedGlitchesMeanTheAnchorWasTheBadOneSoTheNewPositionIsTrusted() {
        feed(LAT0 + 3.0, 1);                                              // the very first fix was wildly wrong

        assertThat(feed(LAT0, 4)).isEqualTo(Verdict.GLITCH);
        assertThat(feed(LAT0 + 0.0001, 7)).isEqualTo(Verdict.GLITCH);
        assertThat(feed(LAT0 + 0.0002, 10)).isEqualTo(Verdict.RE_ANCHORED);

        assertThat(state.meters()).isZero();                              // no distance invented from the bad point
        assertThat(feed(LAT0 + 0.0022, 20)).isEqualTo(Verdict.COUNTED);   // and driving from the new anchor counts again
        assertThat(state.meters()).isBetween(200.0, 240.0);
    }

    @Test
    void aLongGapWithPlausibleSpeedIsStillCountedAsStraightLine() {
        feed(LAT0, 1);

        assertThat(feed(LAT0 + 0.5, 1800)).isEqualTo(Verdict.COUNTED);    // 55 km in 30 minutes (tunnel, no signal): 110 km/h

        assertThat(state.meters()).isCloseTo(TripMeterMath.distanceMeters(LAT0, LNG, LAT0 + 0.5, LNG), within(1.0));
    }

    @Test
    void theDemoSimulatorsSpeedIsNotTreatedAsAGlitch() {
        feed(LAT0, 1);

        // 130 m every 3 s is about 156 km/h, under the 200 km/h limit
        assertThat(feed(LAT0 + 0.00117, 4)).isEqualTo(Verdict.COUNTED);
    }

    @Test
    void nonsenseCoordinatesAreRefused() {
        feed(LAT0, 1);
        double before = state.meters();

        for (double bad : new double[] {Double.NaN, 91, -91}) {
            Step step = TripMeterMath.apply(state, new DriverPing(DRIVER, bad, LNG, T0 + 50_000), LIMITS);
            assertThat(step.verdict()).isEqualTo(Verdict.INVALID);
        }
        assertThat(TripMeterMath.apply(state, new DriverPing(DRIVER, LAT0, 181, T0 + 50_000), LIMITS).verdict()).isEqualTo(Verdict.INVALID);
        assertThat(TripMeterMath.apply(state, new DriverPing(DRIVER, LAT0, LNG, 0), LIMITS).verdict()).isEqualTo(Verdict.INVALID);
        assertThat(state.meters()).isEqualTo(before);
    }

    @Test
    void onlyDecisionsThatChangeTheMeterAreWrittenBack() {
        assertThat(Verdict.COUNTED.changesState()).isTrue();
        assertThat(Verdict.ANCHORED.changesState()).isTrue();
        assertThat(Verdict.GLITCH.changesState()).isTrue();
        assertThat(Verdict.RE_ANCHORED.changesState()).isTrue();
        assertThat(Verdict.WOBBLE.changesState()).isFalse();
        assertThat(Verdict.DUPLICATE.changesState()).isFalse();
        assertThat(Verdict.INVALID.changesState()).isFalse();
    }

    @Test
    void parsesPingsFromDriverServiceAndRefusesAnythingElse() {
        String ok = "{\"driverId\":\"" + DRIVER + "\",\"lat\":12.97,\"lng\":77.6,\"heading\":90.0,\"ts\":" + T0 + "}";

        assertThat(DriverPing.parse(ok)).contains(new DriverPing(DRIVER, 12.97, 77.6, T0));
        assertThat(DriverPing.parse("not json")).isEmpty();
        assertThat(DriverPing.parse("{\"lat\":1,\"lng\":2,\"ts\":3}")).isEmpty();                       // no driver
        assertThat(DriverPing.parse("{\"driverId\":\"x\",\"lat\":1,\"lng\":2,\"ts\":3}")).isEmpty();    // not a UUID
        assertThat(DriverPing.parse("{\"driverId\":\"" + DRIVER + "\",\"lat\":\"a\",\"lng\":2,\"ts\":3}")).isEmpty();
    }
}
