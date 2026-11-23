package com.ridebooking.auth.infrastructure.security.google;

import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.infrastructure.security.SessionBoundGrant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

/** Request carrying a Google ID token. The token is a credential: never put it in additionalParameters. */
public class GoogleGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken
        implements SessionBoundGrant {

    private final String idToken;
    private final Role roleForNewUser;
    private final Set<String> scopes;
    private final UUID sessionId;

    public GoogleGrantAuthenticationToken(Authentication clientPrincipal, String idToken,
                                          Role roleForNewUser, Set<String> scopes) {
        this(clientPrincipal, idToken, roleForNewUser, scopes, null);
    }

    private GoogleGrantAuthenticationToken(Authentication clientPrincipal, String idToken,
                                           Role roleForNewUser, Set<String> scopes, UUID sessionId) {
        super(GoogleGrant.TYPE, clientPrincipal, Map.of());
        this.idToken = idToken;
        this.roleForNewUser = roleForNewUser;
        this.scopes = Set.copyOf(scopes);
        this.sessionId = sessionId;
    }

    public GoogleGrantAuthenticationToken withSessionId(UUID sid) {
        return new GoogleGrantAuthenticationToken((Authentication) getPrincipal(), idToken, roleForNewUser, scopes, sid);
    }

    public String getIdToken() { return idToken; }
    public Role getRoleForNewUser() { return roleForNewUser; }
    public Set<String> getScopes() { return scopes; }

    @Override
    public UUID getSessionId() { return sessionId; }
}
