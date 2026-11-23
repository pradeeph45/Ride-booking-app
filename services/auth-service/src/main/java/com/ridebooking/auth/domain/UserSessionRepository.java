package com.ridebooking.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** Candidates for the "my devices" list; idle/absolute expiry is applied by the caller. */
    List<UserSession> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(UUID userId, Instant now);
}
