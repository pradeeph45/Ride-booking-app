package com.ridebooking.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One login on one device. Its id equals the OAuth2 authorization id, so it survives refreshes
 * and appears as the "sid" claim in every access token issued for that login.
 */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserSession() {
        // for JPA
    }

    public static UserSession start(UUID id, UUID userId, String deviceName, String ipAddress,
                                    String userAgent, Instant now, Duration absoluteTtl) {
        UserSession s = new UserSession();
        s.id = id;
        s.userId = userId;
        s.deviceName = truncate(deviceName, 100);
        s.ipAddress = truncate(ipAddress, 45);
        s.userAgent = truncate(userAgent, 255);
        s.createdAt = now;
        s.lastSeenAt = now;
        s.expiresAt = now.plus(absoluteTtl);
        return s;
    }

    /** Active = not revoked, inside the absolute lifetime, and used within the idle window. */
    public boolean isActive(Instant now, Duration idleTtl) {
        return revokedAt == null && now.isBefore(expiresAt) && now.isBefore(lastSeenAt.plus(idleTtl));
    }

    public void touch(Instant now) {
        if (now.isAfter(lastSeenAt)) {
            lastSeenAt = now;
        }
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public boolean isRevoked() { return revokedAt != null; }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getDeviceName() { return deviceName; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
