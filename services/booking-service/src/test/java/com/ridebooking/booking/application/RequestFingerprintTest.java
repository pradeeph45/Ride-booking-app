package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    final Place a = Place.of(12.9756, 77.6050, "MG Road");
    final Place b = Place.of(12.9352, 77.6245, "Koramangala");

    @Test
    void sameRequestAlwaysGivesTheSameFingerprint() {
        assertThat(RequestFingerprint.of(ServiceType.CAR_MINI, a, b))
                .isEqualTo(RequestFingerprint.of(ServiceType.CAR_MINI, Place.of(12.9756, 77.6050, "MG Road"), Place.of(12.9352, 77.6245, "Koramangala")));
    }

    @Test
    void anyMeaningfulChangeGivesADifferentFingerprint() {
        String base = RequestFingerprint.of(ServiceType.CAR_MINI, a, b);

        assertThat(RequestFingerprint.of(ServiceType.BIKE, a, b)).isNotEqualTo(base);                        // vehicle
        assertThat(RequestFingerprint.of(ServiceType.CAR_MINI, b, a)).isNotEqualTo(base);                    // direction
        assertThat(RequestFingerprint.of(ServiceType.CAR_MINI, Place.of(12.9757, 77.6050, "MG Road"), b)).isNotEqualTo(base); // coordinates
        assertThat(RequestFingerprint.of(ServiceType.CAR_MINI, a, Place.of(12.9352, 77.6245, "Elsewhere"))).isNotEqualTo(base); // label
    }

    @Test
    void aMissingAddressIsNotTheSameAsAnEmptyOneButIsStable() {
        Place noLabel = Place.of(12.9756, 77.6050, null);

        assertThat(RequestFingerprint.of(ServiceType.AUTO, noLabel, b)).isEqualTo(RequestFingerprint.of(ServiceType.AUTO, noLabel, b));
    }

    @Test
    void isA64CharacterHexDigestThatRevealsNothingReadable() {
        assertThat(RequestFingerprint.of(ServiceType.AUTO, a, b)).matches("[0-9a-f]{64}");
    }

    @Test
    void fieldBoundariesCannotBeFaked() {
        // "ab" + "c" must not collide with "a" + "bc": fields are delimited, not just concatenated
        Place p1 = Place.of(1, 2, "ab|c");
        Place p2 = Place.of(1, 2, "ab");
        assertThat(RequestFingerprint.of(ServiceType.AUTO, p1, b)).isNotEqualTo(RequestFingerprint.of(ServiceType.AUTO, p2, b));
    }
}
