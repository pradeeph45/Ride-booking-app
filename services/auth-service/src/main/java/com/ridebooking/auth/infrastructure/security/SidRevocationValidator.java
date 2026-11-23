package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.application.session.SessionRevocationStore;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects access tokens whose session was revoked, on every request. Fails CLOSED: if the revocation
 * store cannot be reached, the token is refused rather than trusted.
 */
public class SidRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED =
            new OAuth2Error("invalid_token", "The session has ended", null);
    private static final OAuth2Error UNAVAILABLE =
            new OAuth2Error("invalid_token", "Session status could not be verified", null);

    private final SessionRevocationStore store;

    public SidRevocationValidator(SessionRevocationStore store) {
        this.store = store;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            return OAuth2TokenValidatorResult.success(); // e.g. client-credentials tokens have no user session
        }
        try {
            return store.isRevoked(UUID.fromString(sid))
                    ? OAuth2TokenValidatorResult.failure(REVOKED)
                    : OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(UNAVAILABLE);
        }
    }
}
