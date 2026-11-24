package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FareCalculatorTest {

    final FareCalculator calculator = new FareCalculator();
    // MG Road to Koramangala, Bengaluru: about 4.9 km in a straight line
    final Place mgRoad = Place.of(12.9756, 77.6050, "MG Road");
    final Place koramangala = Place.of(12.9352, 77.6245, "Koramangala");

    @Test
    void distanceIsStraightLineScaledToRoadDistance() {
        FareQuote quote = calculator.estimate(ServiceType.CAR_MINI, mgRoad, koramangala);

        // ~4.9 km straight x 1.3 road factor = ~6.4 km
        assertThat(quote.distanceKm()).isBetween(new BigDecimal("6.00"), new BigDecimal("6.80"));
        assertThat(quote.estimatedMinutes()).isGreaterThan(5);
    }

    @Test
    void fareIsBasePlusDistancePlusTimeInRupeesWithTwoDecimals() {
        FareQuote quote = calculator.estimate(ServiceType.CAR_MINI, mgRoad, koramangala);

        assertThat(quote.currency()).isEqualTo("INR");
        assertThat(quote.fare().scale()).isEqualTo(2);
        // base 50 + ~6.4 km x 12 + ~16 min x 1.5 = roughly 150
        assertThat(quote.fare()).isBetween(new BigDecimal("120.00"), new BigDecimal("190.00"));
    }

    @Test
    void biggerVehiclesCostMoreForTheSameTrip() {
        List<BigDecimal> fares = Arrays.stream(ServiceType.values())
                .map(t -> calculator.estimate(t, mgRoad, koramangala).fare()).toList();

        // enum order is BIKE, AUTO, CAR_MINI, CAR_SEDAN, CAR_SUV
        assertThat(fares).isSorted();
        assertThat(fares).doesNotHaveDuplicates();
    }

    @Test
    void veryShortTripsPayTheMinimumFare() {
        Place near = Place.of(12.9756, 77.6070, "around the corner"); // ~220 m

        FareQuote quote = calculator.estimate(ServiceType.CAR_SEDAN, mgRoad, near);

        assertThat(quote.fare()).isEqualByComparingTo("100.00"); // CAR_SEDAN minimum fare
    }

    @Test
    void pickupAndDropAtTheSamePlaceAreRejected() {
        assertThatThrownBy(() -> calculator.estimate(ServiceType.BIKE, mgRoad, mgRoad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too close");
    }

    @Test
    void identicalInputsAlwaysGiveIdenticalQuotes() {
        assertThat(calculator.estimate(ServiceType.AUTO, mgRoad, koramangala))
                .isEqualTo(calculator.estimate(ServiceType.AUTO, mgRoad, koramangala));
    }

    @Test
    void coordinatesOutsideTheEarthAreRejected() {
        assertThatThrownBy(() -> Place.of(91, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Place.of(0, 181, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Place.of(Double.NaN, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
