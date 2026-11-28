package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pricing a trip from what was actually driven. Uses the same rate card as the estimate. */
class MeteredFareTest {

    final FareCalculator calculator = new FareCalculator();
    final BigDecimal estimate = new BigDecimal("170.00");

    @Test
    void usesTheSameRateCardAsTheEstimate() {
        // CAR_MINI: 50 base + 12/km + 1.50/min.  5.00 km, 15 min: 50 + 60 + 22.50
        MeteredFare fare = calculator.metered(ServiceType.CAR_MINI, estimate, 5000, 15 * 60);

        assertThat(fare.distanceKm()).isEqualByComparingTo("5.00");
        assertThat(fare.minutes()).isEqualTo(15);
        assertThat(fare.fare()).isEqualByComparingTo("132.50");
        assertThat(fare.capped()).isFalse();
    }

    @Test
    void roundsDistanceToTwoDecimalsAndStartedMinutesUp() {
        MeteredFare fare = calculator.metered(ServiceType.CAR_MINI, estimate, 5004.9, 15 * 60 + 1);

        assertThat(fare.distanceKm()).isEqualByComparingTo("5.00");
        assertThat(fare.minutes()).isEqualTo(16);                        // a minute that has begun is charged
    }

    @Test
    void neverBelowTheServiceMinimum() {
        MeteredFare fare = calculator.metered(ServiceType.CAR_MINI, estimate, 200, 60);   // a very short hop

        assertThat(fare.fare()).isEqualByComparingTo("80.00");           // CAR_MINI minimum
    }

    @Test
    void aDetourCannotRunTheBillPastOneAndAHalfTimesTheEstimate() {
        // 40 km in 90 minutes would be 50 + 480 + 135 = 665, far above 1.5 x 170 = 255
        MeteredFare fare = calculator.metered(ServiceType.CAR_MINI, estimate, 40_000, 90 * 60);

        assertThat(fare.capped()).isTrue();
        assertThat(fare.fare()).isEqualByComparingTo("255.00");
        assertThat(fare.distanceKm()).isEqualByComparingTo("40.00");     // the real figures are still reported
        assertThat(fare.minutes()).isEqualTo(90);
    }

    @Test
    void theCeilingItselfIsAllowedAndOneStepOverIsCut() {
        // 10.00 km in 30 min prices at 50 + 120 + 45 = 215.00, and 1.5 x 143.33 rounds to exactly 215.00
        BigDecimal e = new BigDecimal("143.33");

        MeteredFare atCeiling = calculator.metered(ServiceType.CAR_MINI, e, 10_000, 30 * 60);
        MeteredFare justOver = calculator.metered(ServiceType.CAR_MINI, e, 10_010, 30 * 60);   // raw 215.12

        assertThat(atCeiling.capped()).isFalse();
        assertThat(atCeiling.fare()).isEqualByComparingTo("215.00");
        assertThat(justOver.capped()).isTrue();
        assertThat(justOver.fare()).isEqualByComparingTo("215.00");
    }

    @Test
    void everyServiceTypeHasItsOwnRates() {
        MeteredFare bike = calculator.metered(ServiceType.BIKE, new BigDecimal("100"), 5000, 15 * 60);   // 20 + 30 + 7.50
        MeteredFare suv = calculator.metered(ServiceType.CAR_SUV, new BigDecimal("400"), 5000, 15 * 60); // 80 + 90 + 30

        assertThat(bike.fare()).isEqualByComparingTo("57.50");
        assertThat(suv.fare()).isEqualByComparingTo("200.00");
    }
}
