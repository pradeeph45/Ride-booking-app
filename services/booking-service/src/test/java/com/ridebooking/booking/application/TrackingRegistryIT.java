package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.application.tracking.TrackingRegistry;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Redis entries that tell the tracking service who may watch which trip. */
@Testcontainers
class TrackingRegistryIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final Instant T = Instant.parse("2026-06-01T10:00:00Z");
    final UUID rider = UUID.randomUUID();
    final UUID driver = UUID.randomUUID();
    StringRedisTemplate redis;
    TrackingRegistry registry;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        registry = new TrackingRegistry(redis);
    }

    private Booking booking() {
        FareQuote quote = new FareQuote(ServiceType.CAR_MINI, new BigDecimal("6.40"), 16, new BigDecimal("148.50"), "INR");
        return Booking.request(rider, ServiceType.CAR_MINI, Place.of(12.9756, 77.6050, "A"), Place.of(12.9352, 77.6245, "B"), quote, T);
    }

    @Test
    void anActiveTripIsRegisteredForBothPartiesToBeAuthorised() {
        Booking b = booking();
        b.assignDriver(driver, T);

        registry.publish(b);

        String entry = redis.opsForValue().get("tracking:booking:" + b.getId());
        assertThat(entry).contains(rider.toString()).contains(driver.toString()).contains("MATCHED");
        assertThat(redis.opsForValue().get("tracking:driver:" + driver)).isEqualTo(b.getId().toString());
        assertThat(redis.getExpire("tracking:booking:" + b.getId())).isPositive();   // never leaks forever, even if cleanup is missed
    }

    @Test
    void theEntryFollowsTheTripThroughItsStages() {
        Booking b = booking();
        b.assignDriver(driver, T);
        registry.publish(b);

        b.driverArrived(driver, T);
        registry.publish(b);

        assertThat(redis.opsForValue().get("tracking:booking:" + b.getId())).contains("ARRIVED");
    }

    @Test
    void aTripThatHasNotBeenAcceptedIsNotRegistered() {
        Booking b = booking();

        registry.publish(b);

        assertThat(redis.hasKey("tracking:booking:" + b.getId())).isFalse();
    }

    @Test
    void finishedAndCancelledTripsAreRemoved() {
        Booking done = booking();
        done.assignDriver(driver, T);
        registry.publish(done);
        done.driverArrived(driver, T);
        done.start(driver, T);
        done.complete(driver, T);

        registry.publish(done);

        assertThat(redis.hasKey("tracking:booking:" + done.getId())).isFalse();
        assertThat(redis.hasKey("tracking:driver:" + driver)).isFalse();

        UUID driver2 = UUID.randomUUID();
        Booking cancelled = booking();
        cancelled.assignDriver(driver2, T);
        registry.publish(cancelled);
        cancelled.cancelByRider(rider, T);
        registry.publish(cancelled);

        assertThat(redis.hasKey("tracking:booking:" + cancelled.getId())).isFalse();
        assertThat(redis.hasKey("tracking:driver:" + driver2)).isFalse();
    }

    @Test
    void finishingOneTripNeverErasesTheDriversNextTrip() {
        Booking first = booking();
        first.assignDriver(driver, T);
        registry.publish(first);
        first.driverArrived(driver, T);
        first.start(driver, T);

        Booking second = booking();                       // the driver is accepted for a new ride the instant the first ends
        second.assignDriver(driver, T);
        registry.publish(second);
        first.complete(driver, T);
        registry.publish(first);                          // the first trip's clean-up arrives late

        assertThat(redis.opsForValue().get("tracking:driver:" + driver)).isEqualTo(second.getId().toString());
        assertThat(redis.hasKey("tracking:booking:" + second.getId())).isTrue();
        assertThat(redis.hasKey("tracking:booking:" + first.getId())).isFalse();
    }
}
