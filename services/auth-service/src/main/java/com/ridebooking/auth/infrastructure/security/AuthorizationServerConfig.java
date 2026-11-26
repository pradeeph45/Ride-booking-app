package com.ridebooking.auth.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.ridebooking.auth.application.session.DeviceInfo;
import com.ridebooking.auth.application.session.SessionInactiveException;
import com.ridebooking.auth.application.session.SessionRevocationStore;
import com.ridebooking.auth.application.session.SessionService;
import com.ridebooking.auth.domain.UserRepository;
import com.ridebooking.auth.infrastructure.security.otp.OtpGrant;
import com.ridebooking.auth.infrastructure.security.google.GoogleGrant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** Everything the OAuth2 Authorization Server needs: signing keys, clients, token generation, storage. */
@Configuration
class AuthorizationServerConfig {

    /**
     * DEV KEY: generated at startup, so every restart invalidates all issued tokens and the key
     * differs per instance. Production must load a persistent key from a secret store (AWS Secrets
     * Manager / KMS) and publish rotated keys via JWKS.
     */
    @Bean
    RSAKey rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /** Verifies signature, expiry and timestamps, then that the token's session was not revoked. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey, SessionRevocationStore revocations) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new SidRevocationValidator(revocations)));
        return decoder;
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${ridebooking.auth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    /**
     * Adds role, uid and sid to user access tokens, and gates every issue/refresh on the session:
     * a revoked, idle or expired session can no longer mint tokens (the refresh token goes dead).
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(UserRepository users, SessionService sessions) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            // Client-credentials tokens have the client id as principal name and no matching user.
            users.findByEmail(context.getPrincipal().getName()).ifPresent(user -> {
                UUID sid = sessionId(context);
                if (sid != null) {
                    try {
                        sessions.useSession(sid, user.getId(), currentDevice());
                    } catch (SessionInactiveException e) {
                        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
                    }
                    context.getClaims().claim("sid", sid.toString());
                }
                context.getClaims().claim("uid", user.getId().toString());
                context.getClaims().claim("role", user.getRole().name());
            });
        };
    }

    /** The authorization id is the session id: stable across refreshes, known before the first token. */
    private static UUID sessionId(JwtEncodingContext context) {
        if (context.getAuthorization() != null) {
            return UUID.fromString(context.getAuthorization().getId());
        }
        return context.getAuthorizationGrant() instanceof SessionBoundGrant grant ? grant.getSessionId() : null;
    }

    private static DeviceInfo currentDevice() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String userAgent = request.getHeader("User-Agent");
            String name = request.getParameter("device_name");
            return new DeviceInfo(name != null ? name : userAgent, request.getRemoteAddr(), userAgent);
        }
        return DeviceInfo.unknown();
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource,
                                           OAuth2TokenCustomizer<JwtEncodingContext> customizer) {
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwtGenerator.setJwtCustomizer(customizer);
        return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2RefreshTokenGenerator());
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcOperations jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    /**
     * rb-web: public SPA client, Authorization Code + mandatory PKCE, no secret.
     * rb-bff: confidential client (the future gateway/BFF) allowed to use the OTP grant. The SPA
     *         cannot use it directly because a browser cannot keep a client secret.
     */
    @Bean
    RegisteredClientRepository registeredClientRepository(
            PasswordEncoder encoder,
            @Value("${ridebooking.auth.web-redirect-uri}") String webRedirectUri,
            @Value("${ridebooking.auth.bff-client-secret}") String bffSecret,
            @Value("${ridebooking.auth.booking-client-secret}") String bookingSecret) {

        TokenSettings tokens = TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(15))
                .refreshTokenTimeToLive(Duration.ofDays(30))
                .reuseRefreshTokens(false) // rotation: every refresh issues a new refresh token
                .build();

        RegisteredClient web = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("rb-web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(webRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("booking.read")
                .scope("booking.write")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokens)
                .build();

        RegisteredClient bff = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("rb-bff")
                .clientSecret(encoder.encode(bffSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(OtpGrant.TYPE)
                .authorizationGrantType(GoogleGrant.TYPE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("booking.read")
                .scope("booking.write")
                .tokenSettings(tokens)
                .build();

        // booking-service as a MACHINE: client credentials only, one narrow scope. It cannot log users in
        // (no OTP/Google/refresh grants) and gets no refresh token.
        RegisteredClient booking = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("rb-booking")
                .clientSecret(encoder.encode(bookingSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("drivers.read")
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(10)).build())
                .build();

        return new InMemoryRegisteredClientRepository(web, bff, booking);
    }
}
