package com.ridebooking.auth.api.dto;

import com.ridebooking.auth.domain.UserSession;
import java.time.Instant;
import java.util.UUID;

/** A device the user is logged in on. Shows no tokens and no internal user ids. */
public record SessionResponse(UUID id, String deviceName, String ipAddress,
                              Instant createdAt, Instant lastSeenAt, boolean current) {

    public static SessionResponse from(UserSession s, UUID currentSid) {
        return new SessionResponse(s.getId(), s.getDeviceName(), s.getIpAddress(),
                s.getCreatedAt(), s.getLastSeenAt(), s.getId().equals(currentSid));
    }
}
