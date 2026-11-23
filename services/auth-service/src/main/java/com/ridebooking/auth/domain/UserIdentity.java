package com.ridebooking.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An external login (provider + the provider's stable subject id) attached to a user. */
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    public static final String GOOGLE = "GOOGLE";

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserIdentity() {
        // for JPA
    }

    public static UserIdentity link(UUID userId, String provider, String subject, String email) {
        UserIdentity i = new UserIdentity();
        i.id = UUID.randomUUID();
        i.userId = userId;
        i.provider = provider;
        i.subject = subject;
        i.email = email;
        i.createdAt = Instant.now();
        return i;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getSubject() { return subject; }
    public String getEmail() { return email; }
}
