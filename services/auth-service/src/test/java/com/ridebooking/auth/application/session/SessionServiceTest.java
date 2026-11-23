package com.ridebooking.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridebooking.auth.domain.UserSession;
import com.ridebooking.auth.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SessionServiceTest {

    static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    static final DeviceInfo DEVICE = new DeviceInfo("Pixel 8", "10.0.0.1", "Mozilla");

    final Map<UUID, UserSession> db = new HashMap<>();
    final Set<UUID> revoked = new HashSet<>();
    final UserSessionRepository repo = Mockito.mock(UserSessionRepository.class);
    final SessionRevocationStore store = new SessionRevocationStore() {
        public void revoke(UUID sid, Duration ttl) { revoked.add(sid); }
        public boolean isRevoked(UUID sid) { return revoked.contains(sid); }
    };
    final SessionProperties props =
            new SessionProperties(Duration.ofDays(30), Duration.ofDays(14), Duration.ofMinutes(20));
    SessionService service;

    @BeforeEach
    void setUp() {
        Mockito.when(repo.findById(Mockito.any())).thenAnswer(i -> Optional.ofNullable(db.get(i.<UUID>getArgument(0))));
        Mockito.when(repo.save(Mockito.any())).thenAnswer(i -> {
            UserSession s = i.getArgument(0);
            db.put(s.getId(), s);
            return s;
        });
        Mockito.when(repo.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(Mockito.any(), Mockito.any()))
                .thenAnswer(i -> db.values().stream().filter(s -> s.getUserId().equals(i.getArgument(0))).toList());
        service = new SessionService(repo, store, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstUseCreatesSessionAndSecondUseReusesIt() {
        UUID sid = UUID.randomUUID(), user = UUID.randomUUID();

        service.useSession(sid, user, DEVICE);
        service.useSession(sid, user, DEVICE);

        assertThat(db).hasSize(1);
        assertThat(db.get(sid).getDeviceName()).isEqualTo("Pixel 8");
    }

    @Test
    void revokedSessionCannotBeUsedAgain() {
        UUID sid = UUID.randomUUID(), user = UUID.randomUUID();
        service.useSession(sid, user, DEVICE);

        assertThat(service.revoke(user, sid)).isTrue();

        assertThatThrownBy(() -> service.useSession(sid, user, DEVICE)).isInstanceOf(SessionInactiveException.class);
        assertThat(store.isRevoked(sid)).isTrue();
    }

    @Test
    void cannotRevokeAnotherUsersSession() {
        UUID sid = UUID.randomUUID(), owner = UUID.randomUUID(), attacker = UUID.randomUUID();
        service.useSession(sid, owner, DEVICE);

        assertThat(service.revoke(attacker, sid)).isFalse();
        assertThat(store.isRevoked(sid)).isFalse();
        assertThat(db.get(sid).isRevoked()).isFalse();
    }

    @Test
    void revokeAllEndsEveryActiveSessionOfTheUserOnly() {
        UUID user = UUID.randomUUID(), other = UUID.randomUUID();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        service.useSession(a, user, DEVICE);
        service.useSession(b, user, DEVICE);
        service.useSession(c, other, DEVICE);

        assertThat(service.revokeAll(user)).isEqualTo(2);

        assertThat(revoked).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void idleSessionIsRejected() {
        UUID sid = UUID.randomUUID(), user = UUID.randomUUID();
        db.put(sid, UserSession.start(sid, user, "old", "1.1.1.1", "ua", NOW.minus(Duration.ofDays(20)), Duration.ofDays(30)));

        assertThatThrownBy(() -> service.useSession(sid, user, DEVICE)).isInstanceOf(SessionInactiveException.class);
    }

    @Test
    void sessionIdBelongingToAnotherUserIsRejected() {
        UUID sid = UUID.randomUUID();
        service.useSession(sid, UUID.randomUUID(), DEVICE);

        assertThatThrownBy(() -> service.useSession(sid, UUID.randomUUID(), DEVICE))
                .isInstanceOf(SessionInactiveException.class);
    }

    @Test
    void activeListHidesIdleSessions() {
        UUID user = UUID.randomUUID();
        UUID fresh = UUID.randomUUID(), idle = UUID.randomUUID();
        service.useSession(fresh, user, DEVICE);
        db.put(idle, UserSession.start(idle, user, "old", "1.1.1.1", "ua", NOW.minus(Duration.ofDays(20)), Duration.ofDays(30)));

        List<UserSession> active = service.activeSessions(user);

        assertThat(active).extracting(UserSession::getId).containsExactly(fresh);
    }
}
