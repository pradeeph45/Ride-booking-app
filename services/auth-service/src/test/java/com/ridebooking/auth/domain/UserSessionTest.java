package com.ridebooking.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserSessionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration ABSOLUTE = Duration.ofDays(30);
    private static final Duration IDLE = Duration.ofDays(14);

    private UserSession session() {
        return UserSession.start(UUID.randomUUID(), UUID.randomUUID(), "Pixel 8", "10.0.0.1", "Mozilla/5.0", T0, ABSOLUTE);
    }

    @Test
    void freshSessionIsActive() {
        assertThat(session().isActive(T0.plusSeconds(5), IDLE)).isTrue();
    }

    @Test
    void revokedSessionIsInactiveForever() {
        UserSession s = session();
        s.revoke(T0.plusSeconds(10));

        assertThat(s.isActive(T0.plusSeconds(11), IDLE)).isFalse();
        assertThat(s.isRevoked()).isTrue();
    }

    @Test
    void idleTimeoutEndsSessionUnlessTouched() {
        UserSession s = session();
        assertThat(s.isActive(T0.plus(IDLE).plusSeconds(1), IDLE)).isFalse();

        s.touch(T0.plus(IDLE).minusSeconds(1));
        assertThat(s.isActive(T0.plus(IDLE).plusSeconds(1), IDLE)).isTrue();
    }

    @Test
    void absoluteTimeoutEndsSessionEvenIfBusy() {
        UserSession s = session();
        s.touch(T0.plus(ABSOLUTE).minusSeconds(1));

        assertThat(s.isActive(T0.plus(ABSOLUTE).plusSeconds(1), IDLE)).isFalse();
    }

    @Test
    void revokeIsIdempotentAndKeepsFirstTimestamp() {
        UserSession s = session();
        s.revoke(T0.plusSeconds(1));
        s.revoke(T0.plusSeconds(99));

        assertThat(s.getRevokedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void longUserAgentAndDeviceNamesAreTruncated() {
        UserSession s = UserSession.start(UUID.randomUUID(), UUID.randomUUID(),
                "d".repeat(500), "10.0.0.1", "u".repeat(500), T0, ABSOLUTE);

        assertThat(s.getDeviceName()).hasSize(100);
        assertThat(s.getUserAgent()).hasSize(255);
    }
}
