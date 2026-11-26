package com.ridebooking.driver.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriverProfileTest {

    static final Instant T = Instant.parse("2026-06-01T10:00:00Z");
    final UUID id = UUID.randomUUID();

    private DriverProfile profile() {
        return DriverProfile.register(id, VehicleType.CAR_MINI, "ka 01 ab 1234", "Maruti Swift", T);
    }

    @Test
    void newProfileStartsOfflineWithANormalizedPlate() {
        DriverProfile p = profile();

        assertThat(p.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(p.getPlateNumber()).isEqualTo("KA01AB1234");   // upper-case, no separators
        assertThat(p.getUserId()).isEqualTo(id);
    }

    @Test
    void plateSpacingAndCaseDoNotMakeTwoPlatesDifferent() {
        DriverProfile a = DriverProfile.register(id, VehicleType.BIKE, "ka-01-ab-1234", "Honda", T);
        DriverProfile b = DriverProfile.register(UUID.randomUUID(), VehicleType.BIKE, "  KA-01-AB-1234 ", "Honda", T);

        assertThat(a.getPlateNumber()).isEqualTo(b.getPlateNumber());
    }

    @Test
    void aPlateWrittenWithOrWithoutSeparatorsIsTheSamePlate() {
        UUID other = UUID.randomUUID();
        assertThat(DriverProfile.register(id, VehicleType.BIKE, "KA01AB1234", "Honda", T).getPlateNumber())
                .isEqualTo(DriverProfile.register(other, VehicleType.BIKE, "ka 01 ab 1234", "Honda", T).getPlateNumber());
    }

    @Test
    void recognisesAResubmissionOfTheSameVehicle() {
        DriverProfile p = profile();
        assertThat(p.isSameVehicle(VehicleType.CAR_MINI, "KA01AB1234", "Maruti Swift")).isTrue();
        assertThat(p.isSameVehicle(VehicleType.CAR_MINI, "ka-01-ab-1234", " Maruti Swift ")).isTrue();
        assertThat(p.isSameVehicle(VehicleType.CAR_SUV, "KA01AB1234", "Maruti Swift")).isFalse();
        assertThat(p.isSameVehicle(VehicleType.CAR_MINI, "KA01AB9999", "Maruti Swift")).isFalse();
    }

    @Test
    void rejectsInvalidPlatesAndMissingFields() {
        assertThatThrownBy(() -> DriverProfile.register(id, VehicleType.BIKE, "x", "m", T)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriverProfile.register(id, VehicleType.BIKE, "KA01<script>", "m", T)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriverProfile.register(id, null, "KA01AB1234", "m", T)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DriverProfile.register(id, VehicleType.BIKE, "KA01AB1234", " ", T)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void goesOnlineAndOfflineAndTheChangesAreReported() {
        DriverProfile p = profile();

        assertThat(p.goOnline(T.plusSeconds(1))).isTrue();
        assertThat(p.getStatus()).isEqualTo(DriverStatus.ONLINE);
        assertThat(p.goOnline(T.plusSeconds(2))).isFalse();     // already online: a harmless repeat
        assertThat(p.goOffline(T.plusSeconds(3))).isTrue();
        assertThat(p.goOffline(T.plusSeconds(4))).isFalse();
        assertThat(p.getUpdatedAt()).isEqualTo(T.plusSeconds(3)); // repeats did not touch the record
    }

    @Test
    void changingVehicleTypeReportsItSoTheGeoIndexCanBeRefreshed() {
        DriverProfile p = profile();

        assertThat(p.updateVehicle(VehicleType.CAR_SUV, "KA01AB1234", "Innova", T.plusSeconds(5))).isTrue();   // type changed
        assertThat(p.updateVehicle(VehicleType.CAR_SUV, "KA01AB1234", "Innova Crysta", T.plusSeconds(6))).isFalse(); // only the model
        assertThat(p.getVehicleModel()).isEqualTo("Innova Crysta");
    }
}
