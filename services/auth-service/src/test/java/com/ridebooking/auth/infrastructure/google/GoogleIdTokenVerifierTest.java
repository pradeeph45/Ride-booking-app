package com.ridebooking.auth.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ridebooking.auth.application.google.GoogleIdentity;
import com.ridebooking.auth.application.google.InvalidGoogleTokenException;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** A local RSA key plays the role of Google's signing keys; every rule is checked offline. */
class GoogleIdTokenVerifierTest {

    static final String CLIENT_ID = "my-app.apps.googleusercontent.com";
    static RSAKey googleKey;
    static RSAKey attackerKey;

    @BeforeAll
    static void keys() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("g1").generate();
        attackerKey = new RSAKeyGenerator(2048).keyID("g1").generate(); // same kid, different key
    }

    private GoogleIdTokenVerifier verifier(String clientId) throws Exception {
        return new GoogleIdTokenVerifier(NimbusJwtDecoder.withPublicKey(googleKey.toRSAPublicKey()).build(), clientId);
    }

    private String token(RSAKey signer, Consumer<JwtClaimsSet.Builder> customize) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("https://accounts.google.com")
                .audience(List.of(CLIENT_ID))
                .subject("1234567890")
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("email", "Person@Gmail.com")
                .claim("email_verified", true);
        customize.accept(claims);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signer)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("g1").build(), claims.build()))
                .getTokenValue();
    }

    private void assertRejected(String idToken) throws Exception {
        assertThatThrownBy(() -> verifier(CLIENT_ID).verify(idToken)).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void acceptsAValidTokenAndNormalizesTheEmail() throws Exception {
        GoogleIdentity id = verifier(CLIENT_ID).verify(token(googleKey, c -> {}));

        assertThat(id.subject()).isEqualTo("1234567890");
        assertThat(id.email()).isEqualTo("person@gmail.com");
    }

    @Test
    void acceptsTheOtherIssuerSpellingGoogleUses() throws Exception {
        assertThat(verifier(CLIENT_ID).verify(token(googleKey, c -> c.issuer("accounts.google.com"))).subject())
                .isEqualTo("1234567890");
    }

    @Test
    void rejectsATokenSignedByAnotherKey() throws Exception {
        assertRejected(token(attackerKey, c -> {}));
    }

    @Test
    void rejectsATokenIssuedToADifferentApplication() throws Exception {
        // The classic confused-deputy hole: a valid Google token minted for someone else's app.
        assertRejected(token(googleKey, c -> c.audience(List.of("someone-elses-app.apps.googleusercontent.com"))));
    }

    @Test
    void rejectsAnotherIssuer() throws Exception {
        assertRejected(token(googleKey, c -> c.issuer("https://evil.example.com")));
    }

    @Test
    void rejectsExpiredTokens() throws Exception {
        assertRejected(token(googleKey, c -> c.issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))));
    }

    @Test
    void rejectsUnverifiedEmails() throws Exception {
        assertRejected(token(googleKey, c -> c.claim("email_verified", false)));
    }

    @Test
    void rejectsTokensWithoutEmailOrSubject() throws Exception {
        assertRejected(token(googleKey, c -> c.claim("email", "")));
        assertRejected(token(googleKey, c -> c.subject("")));
    }

    @Test
    void rejectsGarbage() throws Exception {
        assertRejected("not-a-jwt");
        assertRejected("");
    }

    @Test
    void rejectsEverythingWhenGoogleLoginIsNotConfigured() throws Exception {
        assertThatThrownBy(() -> verifier("").verify(token(googleKey, c -> {})))
                .isInstanceOf(InvalidGoogleTokenException.class)
                .hasMessageContaining("not configured");
    }
}
