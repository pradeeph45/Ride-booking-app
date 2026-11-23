package com.ridebooking.auth.infrastructure.security.google;

import com.ridebooking.auth.application.google.GoogleIdentity;
import com.ridebooking.auth.application.google.GoogleIdentityVerifier;
import com.ridebooking.auth.application.google.GoogleLoginRejectedException;
import com.ridebooking.auth.application.google.GoogleLoginService;
import com.ridebooking.auth.application.google.InvalidGoogleTokenException;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.infrastructure.security.UserTokenIssuer;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

/**
 * Verifies the Google ID token, finds / links / creates the matching user, and issues our own tokens.
 * Every failure is the same generic invalid_grant: callers learn nothing about why.
 */
public class GoogleGrantAuthenticationProvider implements AuthenticationProvider {

    private final GoogleIdentityVerifier verifier;
    private final GoogleLoginService loginService;
    private final UserTokenIssuer issuer;

    public GoogleGrantAuthenticationProvider(GoogleIdentityVerifier verifier, GoogleLoginService loginService,
                                             UserTokenIssuer issuer) {
        this.verifier = verifier;
        this.loginService = loginService;
        this.issuer = issuer;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        GoogleGrantAuthenticationToken request = (GoogleGrantAuthenticationToken) authentication;

        OAuth2ClientAuthenticationToken clientAuth = issuer.authenticatedClient(request.getPrincipal(), GoogleGrant.TYPE);
        Set<String> scopes = issuer.authorizedScopes(clientAuth, request.getScopes());

        User user;
        try {
            GoogleIdentity identity = verifier.verify(request.getIdToken());
            user = loginService.resolveUser(identity, request.getRoleForNewUser());
        } catch (InvalidGoogleTokenException | GoogleLoginRejectedException e) {
            throw UserTokenIssuer.error(OAuth2ErrorCodes.INVALID_GRANT);
        }

        UUID sid = UUID.randomUUID();
        return issuer.issue(clientAuth, GoogleGrant.TYPE, user, scopes, sid, request.withSessionId(sid));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return GoogleGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
