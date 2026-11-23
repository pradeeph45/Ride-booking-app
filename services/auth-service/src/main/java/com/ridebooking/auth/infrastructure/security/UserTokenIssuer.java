package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.domain.User;
import java.security.Principal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Shared by every custom grant: once a grant has proven WHO the user is (OTP, Google, ...), this class
 * produces the tokens and stores the authorization, so all logins end up identical downstream:
 * same JWT claims, same session id, same refresh-token rotation, same revocation.
 */
public class UserTokenIssuer {

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

    public UserTokenIssuer(OAuth2AuthorizationService authorizationService,
                           OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator) {
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
    }

    /** The calling client must be authenticated and allowed to use this grant. */
    public OAuth2ClientAuthenticationToken authenticatedClient(Object principal, AuthorizationGrantType grant) {
        if (!(principal instanceof OAuth2ClientAuthenticationToken clientAuth) || !clientAuth.isAuthenticated()) {
            throw error(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        RegisteredClient client = clientAuth.getRegisteredClient();
        if (client == null || !client.getAuthorizationGrantTypes().contains(grant)) {
            throw error(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }
        return clientAuth;
    }

    /** Requested scopes must be a subset of what the client is registered for; none requested means all. */
    public Set<String> authorizedScopes(OAuth2ClientAuthenticationToken clientAuth, Set<String> requested) {
        RegisteredClient client = clientAuth.getRegisteredClient();
        Set<String> scopes = requested.isEmpty() ? client.getScopes() : requested;
        if (!client.getScopes().containsAll(scopes)) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE);
        }
        return scopes;
    }

    /**
     * @param sid   new session id; also used as the OAuth2 authorization id so refreshes keep it
     * @param grant the grant request, already tagged with {@code sid} (the token customizer reads it)
     */
    public OAuth2AccessTokenAuthenticationToken issue(OAuth2ClientAuthenticationToken clientAuth,
                                                      AuthorizationGrantType grantType, User user,
                                                      Set<String> scopes, UUID sid, Authentication grant) {
        RegisteredClient client = clientAuth.getRegisteredClient();
        Authentication userPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        DefaultOAuth2TokenContext.Builder base = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(userPrincipal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(scopes)
                .authorizationGrantType(grantType)
                .authorizationGrant(grant);

        OAuth2Token generatedAccess = tokenGenerator.generate(base.tokenType(OAuth2TokenType.ACCESS_TOKEN).build());
        if (generatedAccess == null) {
            throw error(OAuth2ErrorCodes.SERVER_ERROR);
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                generatedAccess.getTokenValue(), generatedAccess.getIssuedAt(), generatedAccess.getExpiresAt(), scopes);

        OAuth2Authorization.Builder authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(sid.toString())
                .principalName(user.getEmail())
                .authorizationGrantType(grantType)
                .authorizedScopes(new HashSet<>(scopes))
                .attribute(Principal.class.getName(), userPrincipal)
                .token(accessToken, metadata -> {
                    if (generatedAccess instanceof Jwt jwt) {
                        metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, jwt.getClaims());
                    }
                });

        OAuth2RefreshToken refreshToken = null;
        if (client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            OAuth2Token generatedRefresh = tokenGenerator.generate(base.tokenType(OAuth2TokenType.REFRESH_TOKEN).build());
            if (generatedRefresh instanceof OAuth2RefreshToken rt) {
                refreshToken = rt;
                authorization.refreshToken(rt);
            }
        }
        authorizationService.save(authorization.build());

        return new OAuth2AccessTokenAuthenticationToken(client, clientAuth, accessToken, refreshToken);
    }

    public static OAuth2AuthenticationException error(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
