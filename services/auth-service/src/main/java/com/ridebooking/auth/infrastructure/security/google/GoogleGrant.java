package com.ridebooking.auth.infrastructure.security.google;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

public final class GoogleGrant {

    /** Custom OAuth2 grant: the client exchanges a verified Google ID token for our own tokens. */
    public static final AuthorizationGrantType TYPE =
            new AuthorizationGrantType("urn:ridebooking:grant-type:google-id-token");

    private GoogleGrant() {}
}
