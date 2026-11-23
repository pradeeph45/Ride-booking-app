package com.ridebooking.auth.application.otp;

import java.time.Duration;
import java.util.Optional;

/** Port: short-lived OTP state. Redis in production, an in-memory fake in unit tests. */
public interface OtpStore {

    /** Atomically starts the resend cooldown. Returns false if one is already running. */
    boolean tryStartCooldown(String email, Duration cooldown);

    /** Stores the hashed code with a TTL and resets the attempt counter. */
    void put(String email, String codeHash, Duration ttl);

    Optional<String> get(String email);

    /** Increments and returns the number of verification attempts for the current code. */
    long incrementAttempts(String email, Duration ttl);

    /** Deletes the code. Returns true only for the caller that actually removed it (single-use guarantee). */
    boolean delete(String email);
}
