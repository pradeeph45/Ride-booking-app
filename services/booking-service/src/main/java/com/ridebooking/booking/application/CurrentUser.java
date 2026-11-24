package com.ridebooking.booking.application;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, taken only from the verified token and never from request data. */
public record CurrentUser(UUID id, Role role) {

    public enum Role { RIDER, DRIVER, ADMIN }

    /** @throws IllegalArgumentException when the token is not a user token (e.g. a client-credentials token) */
    public static CurrentUser from(Jwt jwt) {
        String uid = jwt.getClaimAsString("uid");
        String role = jwt.getClaimAsString("role");
        if (uid == null || role == null) {
            throw new IllegalArgumentException("Not a user token");
        }
        return new CurrentUser(UUID.fromString(uid), Role.valueOf(role));
    }
}
