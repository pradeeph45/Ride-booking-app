package com.ridebooking.auth.application.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Temporary lockout after repeated failed password logins. State lives in Redis so every
 * auth-service instance agrees; it expires on its own, so a locked user is never stuck forever.
 * Counts are kept per username even for unknown emails, so locking is not an account-existence oracle.
 */
@Service
public class LoginAttemptService {

    private final StringRedisTemplate redis;
    private final LoginAttemptProperties props;

    public LoginAttemptService(StringRedisTemplate redis, LoginAttemptProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void recordFailure(String username) {
        String key = key(username);
        redis.opsForValue().increment(key);
        redis.expire(key, props.lockDuration());
    }

    public void reset(String username) {
        redis.delete(key(username));
    }

    public boolean isLocked(String username) {
        String count = redis.opsForValue().get(key(username));
        return count != null && Long.parseLong(count) >= props.maxFailures();
    }

    private static String key(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "login:fail:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
