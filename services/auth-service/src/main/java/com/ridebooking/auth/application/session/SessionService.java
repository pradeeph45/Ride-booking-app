package com.ridebooking.auth.application.session;

import com.ridebooking.auth.domain.UserSession;
import com.ridebooking.auth.domain.UserSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the lifecycle of logins. The database is the source of truth; Redis only mirrors revocations
 * so that every request can be checked cheaply (see SidRevocationValidator).
 */
@Service
public class SessionService {

    private final UserSessionRepository sessions;
    private final SessionRevocationStore revocations;
    private final SessionProperties props;
    private final Clock clock;

    public SessionService(UserSessionRepository sessions, SessionRevocationStore revocations,
                          SessionProperties props, Clock clock) {
        this.sessions = sessions;
        this.revocations = revocations;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Called each time an access token is minted (first login and every refresh). Creates the session
     * on first use, then enforces revocation, idle and absolute timeouts, and records activity.
     */
    @Transactional
    public void useSession(UUID sid, UUID userId, DeviceInfo device) {
        Instant now = clock.instant();
        UserSession session = sessions.findById(sid).orElseGet(() -> create(sid, userId, device, now));
        if (!session.getUserId().equals(userId) || !session.isActive(now, props.idleTtl())) {
            throw new SessionInactiveException();
        }
        session.touch(now);
        sessions.save(session);
    }

    private UserSession create(UUID sid, UUID userId, DeviceInfo device, Instant now) {
        UserSession fresh = UserSession.start(sid, userId, device.deviceName(), device.ipAddress(),
                device.userAgent(), now, props.absoluteTtl());
        try {
            return sessions.save(fresh);
        } catch (DataIntegrityViolationException raceLost) {
            // another request created the same sid a moment ago; use theirs
            return sessions.findById(sid).orElseThrow(SessionInactiveException::new);
        }
    }

    @Transactional(readOnly = true)
    public List<UserSession> activeSessions(UUID userId) {
        Instant now = clock.instant();
        return sessions.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(userId, now).stream()
                .filter(s -> s.isActive(now, props.idleTtl()))
                .toList();
    }

    /** Revokes one session. Returns false (not "forbidden") when it does not exist or is not the caller's. */
    @Transactional
    public boolean revoke(UUID userId, UUID sid) {
        Optional<UserSession> found = sessions.findById(sid).filter(s -> s.getUserId().equals(userId));
        found.ifPresent(this::revokeSession);
        return found.isPresent();
    }

    @Transactional
    public int revokeAll(UUID userId) {
        List<UserSession> active = activeSessions(userId);
        active.forEach(this::revokeSession);
        return active.size();
    }

    private void revokeSession(UserSession session) {
        session.revoke(clock.instant());
        sessions.save(session);
        revocations.revoke(session.getId(), props.revocationTtl());
    }

    public boolean isRevoked(UUID sid) {
        return revocations.isRevoked(sid);
    }
}
