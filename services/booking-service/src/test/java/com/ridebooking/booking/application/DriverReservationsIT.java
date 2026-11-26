package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.booking.application.matching.DriverReservations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Redis lock that stops a driver being offered two rides at once, against a real Redis. */
@Testcontainers
class DriverReservationsIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    DriverReservations reservations;
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        reservations = new DriverReservations(redis);
    }

    @Test
    void aFreeDriverCanBeReservedOnceAndThenIsTaken() {
        UUID driver = UUID.randomUUID();

        assertThat(reservations.tryReserve(driver, UUID.randomUUID(), Duration.ofSeconds(20))).isTrue();
        assertThat(reservations.tryReserve(driver, UUID.randomUUID(), Duration.ofSeconds(20))).isFalse();
        assertThat(reservations.isReserved(driver)).isTrue();
    }

    @Test
    void differentDriversAreIndependent() {
        assertThat(reservations.tryReserve(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(20))).isTrue();
        assertThat(reservations.tryReserve(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(20))).isTrue();
    }

    @Test
    void onlyTheRideThatHoldsTheReservationCanReleaseIt() {
        UUID driver = UUID.randomUUID(), rideA = UUID.randomUUID(), rideB = UUID.randomUUID();
        reservations.tryReserve(driver, rideA, Duration.ofSeconds(20));

        reservations.release(driver, rideB);                            // a different ride cannot free it
        assertThat(reservations.isReserved(driver)).isTrue();

        reservations.release(driver, rideA);
        assertThat(reservations.isReserved(driver)).isFalse();
        assertThat(reservations.tryReserve(driver, rideB, Duration.ofSeconds(20))).isTrue();
    }

    @Test
    void releasingTwiceOrReleasingNothingIsHarmless() {
        UUID driver = UUID.randomUUID(), ride = UUID.randomUUID();
        reservations.release(driver, ride);
        reservations.tryReserve(driver, ride, Duration.ofSeconds(20));
        reservations.release(driver, ride);
        reservations.release(driver, ride);

        assertThat(reservations.isReserved(driver)).isFalse();
    }

    @Test
    void aReservationExpiresOnItsOwnSoACrashedServiceCannotLockADriverForever() throws Exception {
        UUID driver = UUID.randomUUID();
        reservations.tryReserve(driver, UUID.randomUUID(), Duration.ofMillis(300));

        Thread.sleep(450);

        assertThat(reservations.isReserved(driver)).isFalse();
        assertThat(reservations.tryReserve(driver, UUID.randomUUID(), Duration.ofSeconds(20))).isTrue();
    }

    @Test
    void ofManyRidesRacingForTheSameDriverExactlyOneWins() throws Exception {
        UUID driver = UUID.randomUUID();
        int threads = 40;
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> futures = IntStream.range(0, threads).<Callable<Boolean>>mapToObj(i -> () -> {
                start.await();
                return reservations.tryReserve(driver, UUID.randomUUID(), Duration.ofSeconds(20));
            }).map(pool::submit).toList();
            start.countDown();
            for (Future<Boolean> f : futures) results.add(f.get());
        }

        assertThat(results.stream().filter(r -> r)).hasSize(1);          // no double-assignment, however tight the race
    }
}
