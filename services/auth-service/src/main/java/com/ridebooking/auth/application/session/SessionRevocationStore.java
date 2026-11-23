package com.ridebooking.auth.application.session;

import java.time.Duration;
import java.util.UUID;

/** Port: fast "is this sid revoked?" lookup used on every authenticated request. */
public interface SessionRevocationStore {

    void revoke(UUID sid, Duration ttl);

    boolean isRevoked(UUID sid);
}
