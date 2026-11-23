package com.ridebooking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.auth.AuthIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The whole story over HTTP: register, request OTP, exchange it for tokens, call a protected API. */
class OtpLoginIT extends AuthIntegrationTest {

    @Test
    void otpGrantIssuesTokensWithRoleClaimsAndTheyOpenProtectedApi() throws Exception {
        String email = registerRider();
        String otp = requestOtp(email);

        String body = exchange(email, otp, BFF_SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.access_token");

        mvc.perform(get("/users/me").header("Authorization", bearer(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("RIDER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void otpIsSingleUse() throws Exception {
        String email = registerRider();
        String otp = requestOtp(email);

        exchange(email, otp, BFF_SECRET).andExpect(status().isOk());
        exchange(email, otp, BFF_SECRET)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void wrongOtpIsRejectedWithGenericInvalidGrant() throws Exception {
        String email = registerRider();
        String otp = requestOtp(email);
        String wrong = "000000".equals(otp) ? "111111" : "000000";

        exchange(email, wrong, BFF_SECRET)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void unknownEmailGets202ButNoCodeIsSentAndGrantFails() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";

        assertThat(requestOtp(ghost)).isNull();
        exchange(ghost, "123456", BFF_SECRET).andExpect(status().isBadRequest());
    }

    @Test
    void wrongClientSecretIsRejected() throws Exception {
        String email = registerRider();
        String otp = requestOtp(email);

        exchange(email, otp, "not-the-secret").andExpect(status().isUnauthorized());
    }

    @Test
    void publicWebClientCannotUseOtpGrant() throws Exception {
        String email = registerRider();
        String otp = requestOtp(email);

        mvc.perform(post("/oauth2/token").accept(MediaType.APPLICATION_JSON).param("client_id", "rb-web")
                        .param("grant_type", GRANT).param("email", email).param("otp", otp))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenRotates() throws Exception {
        Login device = login(registerRider());

        String refreshed = refresh(device.refresh())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(refreshed, "$.refresh_token")).isNotEqualTo(device.refresh());

        // the old refresh token must not work a second time
        refresh(device.refresh()).andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointRejectsMissingAndTamperedTokens() throws Exception {
        mvc.perform(get("/users/me").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());

        String token = login(registerRider()).access();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/users/me").header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishesDiscoveryAndJwks() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://localhost:8081"))
                .andExpect(jsonPath("$.jwks_uri").isNotEmpty());
        mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist()); // private key must never be published
    }
}
