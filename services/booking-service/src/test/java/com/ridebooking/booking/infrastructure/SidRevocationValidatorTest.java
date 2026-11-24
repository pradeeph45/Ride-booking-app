package com.ridebooking.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SidRevocationValidatorTest {

    private static Jwt token(String sid) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (sid != null) b.claim("sid", sid);
        return b.build();
    }

    @Test
    void acceptsATokenWhoseSessionIsStillActive() {
        assertThat(new SidRevocationValidator(sid -> false).validate(token(UUID.randomUUID().toString())).hasErrors()).isFalse();
    }

    @Test
    void rejectsATokenWhoseSessionWasRevoked() {
        UUID sid = UUID.randomUUID();

        assertThat(new SidRevocationValidator(s -> s.equals(sid)).validate(token(sid.toString())).hasErrors()).isTrue();
    }

    @Test
    void failsClosedWhenTheRevocationStoreIsDown() {
        SidRevocationValidator validator = new SidRevocationValidator(sid -> { throw new IllegalStateException("redis down"); });

        assertThat(validator.validate(token(UUID.randomUUID().toString())).hasErrors()).isTrue();
    }

    @Test
    void tokensWithoutASessionAreNotChecked() {
        assertThat(new SidRevocationValidator(sid -> true).validate(token(null)).hasErrors()).isFalse();
    }
}
