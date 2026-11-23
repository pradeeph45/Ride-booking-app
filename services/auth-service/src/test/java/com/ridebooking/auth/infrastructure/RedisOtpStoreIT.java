package com.ridebooking.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.auth.infrastructure.otp.RedisOtpStore;
import java.time.Duration;
import java.util.List;
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

@Testcontainers
class RedisOtpStoreIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    RedisOtpStore store;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        store = new RedisOtpStore(template);
    }

    @Test
    void cooldownCanOnlyBeStartedOnce() {
        assertThat(store.tryStartCooldown("c@x.com", Duration.ofSeconds(30))).isTrue();
        assertThat(store.tryStartCooldown("c@x.com", Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void storesReadsAndExpiresCode() throws Exception {
        store.put("e@x.com", "hash", Duration.ofSeconds(1));
        assertThat(store.get("e@x.com")).contains("hash");

        Thread.sleep(1500);
        assertThat(store.get("e@x.com")).isEmpty();
    }

    @Test
    void countsAttemptsAndPutResetsThem() {
        store.put("a@x.com", "h", Duration.ofMinutes(1));
        assertThat(store.incrementAttempts("a@x.com", Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(store.incrementAttempts("a@x.com", Duration.ofMinutes(1))).isEqualTo(2);

        store.put("a@x.com", "h2", Duration.ofMinutes(1));
        assertThat(store.incrementAttempts("a@x.com", Duration.ofMinutes(1))).isEqualTo(1);
    }

    @Test
    void exactlyOneOfManyConcurrentDeletesWins() throws Exception {
        store.put("race@x.com", "h", Duration.ofMinutes(1));
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> results = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return store.delete("race@x.com");
                    })).toList();
            start.countDown();
            long winners = 0;
            for (Future<Boolean> f : results) {
                if (f.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
        }
    }
}
