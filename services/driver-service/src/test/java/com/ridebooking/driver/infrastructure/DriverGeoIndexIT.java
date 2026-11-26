package com.ridebooking.driver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.driver.MutableClock;
import com.ridebooking.driver.application.DriverGeoIndex;
import com.ridebooking.driver.application.NearbyDriver;
import com.ridebooking.driver.application.PresenceProperties;
import com.ridebooking.driver.domain.VehicleType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Redis geo index against a real Redis: ordering, type separation, radius, staleness and the sweeper. */
@Testcontainers
class DriverGeoIndexIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // central Bengaluru
    static final double LAT = 12.9756, LNG = 77.6050;

    MutableClock clock;
    DriverGeoIndex index;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll(); // each test starts empty
        clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        index = new DriverGeoIndex(redis, clock, new PresenceProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1), true));
    }

    @Test
    void findsDriversNearestFirstWithDistances() {
        UUID near = UUID.randomUUID(), mid = UUID.randomUUID(), far = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, far, 12.9352, 77.6245);   // ~5 km
        index.upsert(VehicleType.CAR_MINI, near, 12.9760, 77.6055);  // ~60 m
        index.upsert(VehicleType.CAR_MINI, mid, 12.9600, 77.6100);   // ~2 km

        List<NearbyDriver> found = index.nearby(VehicleType.CAR_MINI, LAT, LNG, 10, 10);

        assertThat(found).extracting(NearbyDriver::driverId).containsExactly(near, mid, far);
        assertThat(found.get(0).distanceKm()).isLessThan(0.2);
        assertThat(found.get(2).distanceKm()).isBetween(4.0, 6.0);
        assertThat(found).extracting(NearbyDriver::distanceKm).isSorted();
    }

    @Test
    void onlyReturnsDriversOfTheRequestedVehicleType() {
        UUID mini = UUID.randomUUID(), bike = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, mini, 12.9760, 77.6055);
        index.upsert(VehicleType.BIKE, bike, 12.9761, 77.6056);

        assertThat(index.nearby(VehicleType.BIKE, LAT, LNG, 5, 10)).extracting(NearbyDriver::driverId).containsExactly(bike);
        assertThat(index.nearby(VehicleType.CAR_SUV, LAT, LNG, 5, 10)).isEmpty();
    }

    @Test
    void respectsRadiusAndLimit() {
        for (int i = 0; i < 6; i++) {
            index.upsert(VehicleType.AUTO, UUID.randomUUID(), LAT + 0.001 * (i + 1), LNG);
        }
        UUID outsideRadius = UUID.randomUUID();
        index.upsert(VehicleType.AUTO, outsideRadius, 13.30, 77.60);                  // ~37 km away

        List<NearbyDriver> found = index.nearby(VehicleType.AUTO, LAT, LNG, 5, 3);

        assertThat(found).hasSize(3);                                                  // limit
        assertThat(found).extracting(NearbyDriver::driverId).doesNotContain(outsideRadius); // radius
    }

    @Test
    void aMovingDriverIsUpdatedNotDuplicated() {
        UUID d = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, d, 12.9352, 77.6245);
        index.upsert(VehicleType.CAR_MINI, d, 12.9757, 77.6051);   // drove to the rider

        List<NearbyDriver> found = index.nearby(VehicleType.CAR_MINI, LAT, LNG, 10, 10);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).distanceKm()).isLessThan(0.1);
    }

    @Test
    void removedDriversDisappear() {
        UUID d = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, d, 12.9760, 77.6055);

        index.remove(d);

        assertThat(index.nearby(VehicleType.CAR_MINI, LAT, LNG, 10, 10)).isEmpty();
    }

    @Test
    void aDriverWhoChangesVehicleTypeIsOnlyInTheNewSet() {
        UUID d = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, d, 12.9760, 77.6055);

        index.remove(d);                                              // what the profile service does on a type change
        index.upsert(VehicleType.CAR_SUV, d, 12.9760, 77.6055);

        assertThat(index.nearby(VehicleType.CAR_MINI, LAT, LNG, 10, 10)).isEmpty();
        assertThat(index.nearby(VehicleType.CAR_SUV, LAT, LNG, 10, 10)).hasSize(1);
    }

    @Test
    void silentDriversStopBeingOfferedEvenBeforeTheSweeperRuns() {
        UUID quiet = UUID.randomUUID(), chatty = UUID.randomUUID();
        index.upsert(VehicleType.CAR_MINI, quiet, 12.9760, 77.6055);
        clock.advance(Duration.ofSeconds(25));
        index.upsert(VehicleType.CAR_MINI, chatty, 12.9761, 77.6056);  // pinged 25 s later
        clock.advance(Duration.ofSeconds(10));                         // quiet is now 35 s silent, chatty 10 s

        assertThat(index.nearby(VehicleType.CAR_MINI, LAT, LNG, 10, 10)).extracting(NearbyDriver::driverId)
                .containsExactly(chatty);                              // the heartbeat window is enforced at read time
    }

    @Test
    void theSweeperPermanentlyRemovesSilentDriversAndKeepsLiveOnes() {
        UUID quiet = UUID.randomUUID(), live = UUID.randomUUID();
        index.upsert(VehicleType.BIKE, quiet, 12.9760, 77.6055);
        index.upsert(VehicleType.AUTO, live, 12.9761, 77.6056);
        clock.advance(Duration.ofSeconds(20));
        index.upsert(VehicleType.AUTO, live, 12.9761, 77.6056);        // live keeps pinging
        clock.advance(Duration.ofSeconds(15));                         // quiet silent 35 s

        assertThat(index.sweepStale()).isEqualTo(1);
        assertThat(index.sweepStale()).isZero();                       // idempotent: safe to run on every instance

        clock.advance(Duration.ofSeconds(-30));                        // even if the clock rewound, quiet is really gone
        assertThat(index.nearby(VehicleType.BIKE, LAT, LNG, 10, 10)).isEmpty();
        assertThat(index.nearby(VehicleType.AUTO, LAT, LNG, 10, 10)).hasSize(1);
    }
}
