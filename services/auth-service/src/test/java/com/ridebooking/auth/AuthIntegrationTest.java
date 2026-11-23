package com.ridebooking.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.auth.application.google.GoogleIdentity;
import com.ridebooking.auth.application.google.GoogleIdentityVerifier;
import com.ridebooking.auth.application.google.InvalidGoogleTokenException;
import com.ridebooking.auth.application.otp.OtpSender;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared full-stack setup (real MySQL + Redis). All subclasses use identical configuration, so
 * Spring caches one application context and the containers start only once.
 */
@SpringBootTest(properties = {
        "AUTH_DB_PASSWORD=overridden-by-testcontainers",
        "BFF_CLIENT_SECRET=bff-test-secret"})
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, AuthIntegrationTest.CapturingSenderConfig.class})
public abstract class AuthIntegrationTest {

    protected static final String GRANT = "urn:ridebooking:grant-type:otp";
    protected static final String BFF_SECRET = "bff-test-secret";
    protected static final Map<String, String> SENT = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        @Primary
        OtpSender capturingSender() {
            return SENT::put;
        }

        /** Stands in for Google: "ok:{sub}:{email}" is a valid token, anything else is rejected. */
        @Bean
        @Primary
        GoogleIdentityVerifier fakeGoogle() {
            return idToken -> {
                String[] parts = idToken.split(":");
                if (parts.length != 3 || !"ok".equals(parts[0])) {
                    throw new InvalidGoogleTokenException("rejected");
                }
                return new GoogleIdentity(parts[1], parts[2]);
            };
        }
    }

    protected static final String GOOGLE_GRANT = "urn:ridebooking:grant-type:google-id-token";

    protected ResultActions googleExchange(String idToken, String role) throws Exception {
        var request = post("/oauth2/token").with(httpBasic("rb-bff", BFF_SECRET))
                .param("grant_type", GOOGLE_GRANT).param("id_token", idToken);
        if (role != null) request.param("role", role);
        return mvc.perform(request);
    }

    protected static String googleToken(String sub, String email) {
        return "ok:" + sub + ":" + email;
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected StringRedisTemplate redis;

    /** A logged-in device: its tokens and session id. */
    protected record Login(String email, String access, String refresh) {}

    protected String registerRider() throws Exception {
        String email = "rider-" + UUID.randomUUID() + "@example.com";
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"S3cret-pass!\",\"role\":\"RIDER\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    /** Requests an OTP and returns the code the (fake) sender received. Clears the resend cooldown first. */
    protected String requestOtp(String email) throws Exception {
        Set<String> cooldowns = redis.keys("otp:cooldown:*");
        if (cooldowns != null && !cooldowns.isEmpty()) {
            redis.delete(cooldowns);
        }
        SENT.remove(email);
        mvc.perform(post("/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        return SENT.get(email);
    }

    protected ResultActions exchange(String email, String otp, String secret) throws Exception {
        return mvc.perform(post("/oauth2/token")
                .with(httpBasic("rb-bff", secret))
                .param("grant_type", GRANT).param("email", email).param("otp", otp));
    }

    /** Full OTP login for an existing user: one more device. */
    protected Login login(String email) throws Exception {
        String body = exchange(email, requestOtp(email), BFF_SECRET)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Login(email, JsonPath.read(body, "$.access_token"), JsonPath.read(body, "$.refresh_token"));
    }

    protected ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/token").with(httpBasic("rb-bff", BFF_SECRET))
                .param("grant_type", "refresh_token").param("refresh_token", refreshToken));
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
