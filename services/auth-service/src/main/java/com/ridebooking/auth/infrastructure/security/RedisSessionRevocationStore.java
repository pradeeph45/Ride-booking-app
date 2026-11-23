package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.application.session.SessionRevocationStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisSessionRevocationStore implements SessionRevocationStore {

    private final StringRedisTemplate redis;

    public RedisSessionRevocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void revoke(UUID sid, Duration ttl) {
        redis.opsForValue().set(key(sid), "1", ttl);
    }

    @Override
    public boolean isRevoked(UUID sid) {
        return Boolean.TRUE.equals(redis.hasKey(key(sid)));
    }

    private static String key(UUID sid) {
        return "revoked:sid:" + sid;
    }
}
