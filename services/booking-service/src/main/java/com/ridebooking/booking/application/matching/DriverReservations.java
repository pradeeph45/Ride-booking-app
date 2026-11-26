package com.ridebooking.booking.application.matching;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * A short exclusive hold on a driver while an offer to them is pending, so two rides can never be offered to the
 * same driver at once. SET NX is atomic across every service instance. The hold expires by itself, so a crashed
 * service cannot lock a driver forever, and it is released with a compare-and-delete so one ride can never free
 * another ride's hold. (The database backs this up with a unique index on pending offers.)
 */
@Component
public class DriverReservations {

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public DriverReservations(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryReserve(UUID driverId, UUID bookingId, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(driverId), bookingId.toString(), ttl));
    }

    /** Frees the hold only if it is still held for THIS ride. Safe to call any number of times. */
    public void release(UUID driverId, UUID bookingId) {
        redis.execute(RELEASE, List.of(key(driverId)), bookingId.toString());
    }

    public boolean isReserved(UUID driverId) {
        return Boolean.TRUE.equals(redis.hasKey(key(driverId)));
    }

    private static String key(UUID driverId) {
        return "driver:" + driverId + ":reserved";
    }
}
