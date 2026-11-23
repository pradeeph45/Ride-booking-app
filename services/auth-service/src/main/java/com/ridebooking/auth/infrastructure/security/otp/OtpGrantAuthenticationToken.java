package com.ridebooking.auth.infrastructure.security.otp;

import com.ridebooking.auth.infrastructure.security.SessionBoundGrant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

/** Request carrying the OTP. The code is kept out of additionalParameters on purpose. */
public class OtpGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken
        implements SessionBoundGrant {

    private final String email;
    private final String otp;
    private final Set<String> scopes;
    private final UUID sessionId; // null until the provider has verified the OTP and allocated a session

    public OtpGrantAuthenticationToken(Authentication clientPrincipal, String email, String otp, Set<String> scopes) {
        this(clientPrincipal, email, otp, scopes, null);
    }

    private OtpGrantAuthenticationToken(Authentication clientPrincipal, String email, String otp,
                                        Set<String> scopes, UUID sessionId) {
        super(OtpGrant.TYPE, clientPrincipal, Map.of("email", email));
        this.email = email;
        this.otp = otp;
        this.scopes = Set.copyOf(scopes);
        this.sessionId = sessionId;
    }

    /** Same request, tagged with the session id the access token's "sid" claim must carry. */
    public OtpGrantAuthenticationToken withSessionId(UUID sid) {
        return new OtpGrantAuthenticationToken((Authentication) getPrincipal(), email, otp, scopes, sid);
    }

    public String getEmail() { return email; }
    public String getOtp() { return otp; }
    public Set<String> getScopes() { return scopes; }
    @Override
    public UUID getSessionId() { return sessionId; }
}
