package com.ridebooking.auth.infrastructure.otp;

import com.ridebooking.auth.application.otp.OtpStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis adapter. Keys use a SHA-256 of the email so no raw email address is stored in Redis. */
@Component
public class RedisOtpStore implements OtpStore {

    private final StringRedisTemplate redis;

    public RedisOtpStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryStartCooldown(String email, Duration cooldown) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key("cooldown", email), "1", cooldown));
    }

    @Override
    public void put(String email, String codeHash, Duration ttl) {
        redis.opsForValue().set(key("code", email), codeHash, ttl);
        redis.delete(key("attempts", email));
    }

    @Override
    public Optional<String> get(String email) {
        return Optional.ofNullable(redis.opsForValue().get(key("code", email)));
    }

    @Override
    public long incrementAttempts(String email, Duration ttl) {
        String k = key("attempts", email);
        Long n = redis.opsForValue().increment(k);
        redis.expire(k, ttl);
        return n == null ? Long.MAX_VALUE : n;
    }

    @Override
    public boolean delete(String email) {
        redis.delete(key("attempts", email));
        // DEL returns true for exactly one of several concurrent callers: the single-use guarantee.
        return Boolean.TRUE.equals(redis.delete(key("code", email)));
    }

    private static String key(String kind, String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return "otp:" + kind + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
