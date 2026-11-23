package com.ridebooking.gateway.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TokenClientTest {

    static final String TOKEN_JSON =
            "{\"access_token\":\"AT\",\"refresh_token\":\"RT\",\"expires_in\":899,\"token_type\":\"Bearer\"}";

    MockRestServiceServer server;
    TokenClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TokenClient(builder.build(),
                new BffProperties("http://auth", "rb-bff", "s3cr3t", false, "rb_refresh", "http://localhost:5173"));
    }

    @Test
    void otpLoginPostsOtpGrantWithBasicClientAuth() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("rb-bff:s3cr3t".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("http://auth/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=urn%3Aridebooking%3Agrant-type%3Aotp"),
                        org.hamcrest.Matchers.containsString("email=a%40b.com"),
                        org.hamcrest.Matchers.containsString("otp=123456"),
                        org.hamcrest.Matchers.containsString("device_name=Chrome+on+Windows"))))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        TokenResponse tokens = client.otpLogin("a@b.com", "123456", "Chrome on Windows");

        assertThat(tokens.accessToken()).isEqualTo("AT");
        assertThat(tokens.refreshToken()).isEqualTo("RT");
        assertThat(tokens.expiresIn()).isEqualTo(899);
        server.verify();
    }

    @Test
    void googleLoginPostsTheIdTokenWithRoleAndDeviceUnderBasicClientAuth() {
        server.expect(requestTo("http://auth/oauth2/token"))
                .andExpect(header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("rb-bff:s3cr3t".getBytes(StandardCharsets.UTF_8))))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=urn%3Aridebooking%3Agrant-type%3Agoogle-id-token"),
                        org.hamcrest.Matchers.containsString("id_token=GOOGLE.JWT"),
                        org.hamcrest.Matchers.containsString("role=DRIVER"),
                        org.hamcrest.Matchers.containsString("device_name=Firefox"))))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.googleLogin("GOOGLE.JWT", "DRIVER", "Firefox").accessToken()).isEqualTo("AT");
    }

    @Test
    void refreshUsesRefreshGrant() {
        server.expect(requestTo("http://auth/oauth2/token"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=refresh_token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("refresh_token=RT0")))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.refresh("RT0").accessToken()).isEqualTo("AT");
    }

    @Test
    void anyClientErrorBecomesInvalidCredentialsWithoutLeakingDetails() {
        server.expect(requestTo("http://auth/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.otpLogin("a@b.com", "000000", null))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageNotContaining("invalid_grant");
    }

    @Test
    void logoutForwardsTheBearerToken() {
        server.expect(requestTo("http://auth/auth/logout"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer AT"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.logout("AT");
        server.verify();
    }
}
