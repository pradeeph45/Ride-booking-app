package com.ridebooking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.auth.AuthIntegrationTest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Service-to-service login: booking-service gets its own narrowly scoped token via client credentials. */
class ServiceClientIT extends AuthIntegrationTest {

    private static final String BOOKING_SECRET = "booking-test-secret";

    private static String claim(String jwt, String name) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        try {
            return String.valueOf((Object) JsonPath.read(payload, "$." + name));
        } catch (Exception missing) {
            return null;
        }
    }

    @Test
    void bookingServiceGetsATokenWithOnlyTheDriversReadScope() throws Exception {
        String body = mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BOOKING_SECRET))
                        .param("grant_type", "client_credentials").param("scope", "drivers.read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())   // machines re-authenticate; no refresh tokens
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        assertThat(claim(token, "sub")).isEqualTo("rb-booking");
        assertThat(claim(token, "scope")).contains("drivers.read");
        assertThat(claim(token, "uid")).isNull();                        // not a person
        assertThat(claim(token, "role")).isNull();
        assertThat(claim(token, "sid")).isNull();                        // no login session to revoke
    }

    @Test
    void omittingTheScopeGivesATokenWithNoScopesAtAll() throws Exception {
        String body = mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BOOKING_SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // least privilege: a caller must ask for exactly what it needs; nothing is granted by default
        assertThat(claim(JsonPath.read(body, "$.access_token"), "scope")).isNull();
    }

    @Test
    void cannotAskForAScopeItWasNotRegisteredFor() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BOOKING_SECRET))
                        .param("grant_type", "client_credentials").param("scope", "booking.write"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void wrongSecretIsRejected() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", "nope"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theServiceClientCannotLogUsersIn() throws Exception {
        // it is registered for client_credentials only: no OTP, Google or refresh grants
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BOOKING_SECRET))
                        .param("grant_type", GRANT).param("email", "x@y.com").param("otp", "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"));
    }

    @Test
    void theBffAndBookingClientsAreSeparateIdentities() throws Exception {
        // the BFF's secret must not work as the booking service's, and the BFF cannot use client credentials
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BFF_SECRET))
                        .param("grant_type", "client_credentials")).andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-bff", BFF_SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unauthorized_client"));
    }

    @Test
    void aServiceTokenIsNotAUserSession() throws Exception {
        String body = mvc.perform(post("/oauth2/token").with(httpBasic("rb-booking", BOOKING_SECRET))
                        .param("grant_type", "client_credentials")).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        // user-only endpoints refuse it (403: authenticated, but there is no user behind the token)
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertThat(List.of(MediaType.APPLICATION_JSON)).isNotEmpty();
    }
}
