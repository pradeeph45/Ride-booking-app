package com.ridebooking.tracking.infrastructure;

import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens whose login session was ended in auth-service (logout, "log out everywhere"), even though
 * the JWT itself has not expired. Revocations live in Redis, shared with auth-service. Fails CLOSED: if
 * Redis cannot be reached the token is refused rather than trusted.
 */
public class SidRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "The session has ended", null);
    private static final OAuth2Error UNAVAILABLE = new OAuth2Error("invalid_token", "Session status could not be verified", null);

    private final Predicate<UUID> isRevoked;

    public SidRevocationValidator(Predicate<UUID> isRevoked) {
        this.isRevoked = isRevoked;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null) {
            return OAuth2TokenValidatorResult.success();
        }
        try {
            return isRevoked.test(UUID.fromString(sid))
                    ? OAuth2TokenValidatorResult.failure(REVOKED)
                    : OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(UNAVAILABLE);
        }
    }
}
