package com.ridebooking.auth.infrastructure.security;

import java.util.UUID;

/**
 * Implemented by the custom grants (OTP, Google). It carries the id of the session being created so the
 * access token's "sid" claim and the stored authorization share one stable id.
 */
public interface SessionBoundGrant {

    UUID getSessionId();
}
