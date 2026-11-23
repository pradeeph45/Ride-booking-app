package com.ridebooking.auth.infrastructure.security.otp;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

public final class OtpGrant {

    /** Custom OAuth2 grant: the client exchanges (email, otp) for tokens. */
    public static final AuthorizationGrantType TYPE =
            new AuthorizationGrantType("urn:ridebooking:grant-type:otp");

    private OtpGrant() {}
}
