package com.ridebooking.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate root for an account. Created only through {@link #builder()}, so an invalid
 * or plaintext-password user can never exist. JPA annotations sit here for pragmatism;
 * the domain still depends only on the PasswordHasher port, not on a hashing library.
 */
@Entity
@Table(name = "users")
public class User {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 10;

    @Id
    @Column(columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    /** True once the user has proven they control the address (OTP delivered to it, or a Google-verified email). */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected User() {
        // for JPA
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder that enforces invariants in build(): a User can never be created with an
     * invalid email, missing role, weak password, or an unhashed password.
     */
    public static final class Builder {
        private String email;
        private String rawPassword;
        private Role role;
        private PasswordHasher hasher;
        private boolean socialOnly;

        private Builder() {}

        /** Account created through an identity provider: no password, email already verified by the provider. */
        public Builder socialOnly() {
            this.socialOnly = true;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        /** Raw password is hashed inside build(); it is never stored on the entity. */
        public Builder password(String rawPassword, PasswordHasher hasher) {
            this.rawPassword = rawPassword;
            this.hasher = hasher;
            return this;
        }

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        public User build() {
            String normalized = normalizeEmail(email);
            if (role == null) {
                throw new IllegalArgumentException("Role is required");
            }
            if (!socialOnly
                    && (hasher == null || rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH)) {
                throw new IllegalArgumentException(
                        "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
            }
            User user = new User();
            user.id = UUID.randomUUID();
            user.email = normalized;
            user.passwordHash = socialOnly ? null : hasher.hash(rawPassword);
            user.emailVerified = socialOnly;
            user.role = role;
            user.createdAt = Instant.now();
            return user;
        }
    }

    private static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return normalized;
    }

    public boolean passwordMatches(String rawPassword, PasswordHasher hasher) {
        return passwordHash != null && hasher.matches(rawPassword, passwordHash);
    }

    public void markEmailVerified() {
        emailVerified = true;
    }

    /**
     * Removes password login. Used when an identity provider proves ownership of an address that was
     * previously claimed with a password nobody verified (pre-registration takeover defence).
     */
    public void clearPassword() {
        passwordHash = null;
        failedLogins = 0;
    }

    public void recordFailedLogin() {
        failedLogins++;
    }

    public void recordSuccessfulLogin() {
        failedLogins = 0;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public boolean isEmailVerified() { return emailVerified; }
    public int getFailedLogins() { return failedLogins; }
    public Instant getCreatedAt() { return createdAt; }
}
