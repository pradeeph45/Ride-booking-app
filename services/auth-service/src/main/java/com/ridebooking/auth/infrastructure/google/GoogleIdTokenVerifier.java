package com.ridebooking.auth.infrastructure.google;

import com.ridebooking.auth.application.google.GoogleIdentity;
import com.ridebooking.auth.application.google.GoogleIdentityVerifier;
import com.ridebooking.auth.application.google.InvalidGoogleTokenException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies a Google ID token the way Google documents it: signature against Google's published keys,
 * issuer, audience equal to OUR client id, expiry, and a verified email. The audience check is what
 * stops a token minted for some other app from logging into this one.
 */
public class GoogleIdTokenVerifier implements GoogleIdentityVerifier {

    static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final NimbusJwtDecoder decoder;
    private final String clientId;

    public GoogleIdTokenVerifier(NimbusJwtDecoder decoder, String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.decoder = decoder;
        // Spring converts "iss" to a URL by default, but Google may send "accounts.google.com" (no scheme),
        // which is valid per Google's docs. Keep it as a plain string and compare it ourselves.
        this.decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                Map.of(JwtClaimNames.ISS, issuer -> issuer == null ? null : issuer.toString())));
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), issuerIsGoogle(), audienceIsUs()));
    }

    /** Production wiring: Google's real key set, fetched and cached by Nimbus on first use. */
    public static GoogleIdTokenVerifier forGoogle(String clientId) {
        return new GoogleIdTokenVerifier(NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).build(), clientId);
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId.isEmpty()) {
            throw new InvalidGoogleTokenException("Google login is not configured");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidGoogleTokenException("ID token rejected", e);
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw new InvalidGoogleTokenException("ID token has no subject or email");
        }
        if (!emailVerified(jwt)) {
            throw new InvalidGoogleTokenException("Google has not verified this email");
        }
        return new GoogleIdentity(subject, email.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean emailVerified(Jwt jwt) {
        Object claim = jwt.getClaims().get("email_verified"); // boolean, but Google has also sent "true"
        return Boolean.TRUE.equals(claim) || "true".equals(claim);
    }

    private static OAuth2TokenValidator<Jwt> issuerIsGoogle() {
        return jwt -> ISSUERS.contains(jwt.getClaimAsString(JwtClaimNames.ISS))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong issuer", null));
    }

    private OAuth2TokenValidator<Jwt> audienceIsUs() {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        };
    }
}
