package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ridebooking.booking.application.matching.DriverDirectoryException;
import com.ridebooking.booking.application.matching.MatchingProperties;
import com.ridebooking.booking.application.matching.NearbyDriver;
import com.ridebooking.booking.application.matching.RestDriverDirectory;
import com.ridebooking.booking.application.matching.ServiceTokenProvider;
import com.ridebooking.booking.domain.ServiceType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** How booking-service talks to auth-service (to get a token) and driver-service (to find nearby drivers). */
class ServiceClientTest {

    static final String TOKEN_1 = "{\"access_token\":\"token-1\",\"expires_in\":600,\"token_type\":\"Bearer\"}";
    static final String TOKEN_2 = "{\"access_token\":\"token-2\",\"expires_in\":600,\"token_type\":\"Bearer\"}";
    static final MatchingProperties PROPS = new MatchingProperties(true, Duration.ofSeconds(2), Duration.ofSeconds(15), 8,
            10, Duration.ofSeconds(5), "http://drivers", "http://auth", "rb-booking", "s3cr3t");

    final UUID near = UUID.randomUUID();
    MockRestServiceServer server;
    MutableClock clock;
    ServiceTokenProvider tokens;
    RestDriverDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).bufferContent().build();
        RestClient http = builder.build();
        clock = new MutableClock(Instant.parse("2026-06-01T10:00:00Z"));
        tokens = new ServiceTokenProvider(http, PROPS, clock);
        directory = new RestDriverDirectory(http, tokens, PROPS);
    }

    private void expectTokenRequest(String responseBody) {
        String basic = "Basic " + Base64.getEncoder().encodeToString("rb-booking:s3cr3t".getBytes(StandardCharsets.UTF_8));
        server.expect(once(), requestTo("http://auth/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=client_credentials"),
                        org.hamcrest.Matchers.containsString("scope=drivers.read"))))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    // ---- token provider ------------------------------------------------------------------------

    @Test
    void fetchesATokenWithClientCredentialsAndReusesItWhileValid() {
        expectTokenRequest(TOKEN_1);

        assertThat(tokens.token()).isEqualTo("token-1");
        clock.advance(Duration.ofMinutes(5));
        assertThat(tokens.token()).isEqualTo("token-1");     // no second request: the mock allows only one
        server.verify();
    }

    @Test
    void refreshesJustBeforeExpiryNotAfter() {
        expectTokenRequest(TOKEN_1);
        expectTokenRequest(TOKEN_2);

        assertThat(tokens.token()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(560));              // 40 s left: inside the 60 s safety margin
        assertThat(tokens.token()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void invalidatingForcesANewToken() {
        expectTokenRequest(TOKEN_1);
        expectTokenRequest(TOKEN_2);

        tokens.token();
        tokens.invalidate();

        assertThat(tokens.token()).isEqualTo("token-2");
    }

    @Test
    void aFailedTokenRequestSurfacesAsAnErrorAndIsNotCached() {
        server.expect(once(), requestTo("http://auth/oauth2/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest(TOKEN_1);

        assertThatThrownBy(() -> tokens.token()).isInstanceOf(DriverDirectoryException.class);
        assertThat(tokens.token()).isEqualTo("token-1");     // the next call tries again
    }

    // ---- driver directory ----------------------------------------------------------------------

    @Test
    void asksDriverServiceForNearbyDriversWithTheBearerToken() {
        expectTokenRequest(TOKEN_1);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/internal/drivers/nearby")))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andExpect(queryParam("type", "CAR_MINI"))
                .andExpect(queryParam("lat", "12.9756"))
                .andExpect(queryParam("lng", "77.605"))
                .andExpect(queryParam("radiusKm", "8.0"))
                .andExpect(queryParam("limit", "10"))
                .andRespond(withSuccess("[{\"driverId\":\"" + near + "\",\"lat\":12.976,\"lng\":77.6055,\"distanceKm\":0.06}]", MediaType.APPLICATION_JSON));

        List<NearbyDriver> found = directory.nearby(ServiceType.CAR_MINI, 12.9756, 77.605, 8, 10);

        assertThat(found).containsExactly(new NearbyDriver(near, 12.976, 77.6055, 0.06));
        server.verify();
    }

    @Test
    void aRejectedTokenIsRefreshedAndTheCallRetriedExactlyOnce() {
        expectTokenRequest(TOKEN_1);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/")))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));    // e.g. the signing key rotated
        expectTokenRequest(TOKEN_2);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/")))
                .andExpect(header("Authorization", "Bearer token-2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(directory.nearby(ServiceType.BIKE, 12.9, 77.6, 8, 10)).isEmpty();
        server.verify();
    }

    @Test
    void aSecondRejectionGivesUpInsteadOfLoopingForever() {
        expectTokenRequest(TOKEN_1);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectTokenRequest(TOKEN_2);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/"))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> directory.nearby(ServiceType.BIKE, 12.9, 77.6, 8, 10)).isInstanceOf(DriverDirectoryException.class);
    }

    @Test
    void anOutageIsReportedAsADirectoryErrorSoMatchingCanSkipThisRound() {
        expectTokenRequest(TOKEN_1);
        server.expect(once(), requestTo(org.hamcrest.Matchers.startsWith("http://drivers/"))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> directory.nearby(ServiceType.BIKE, 12.9, 77.6, 8, 10)).isInstanceOf(DriverDirectoryException.class);
    }

    /** A clock tests can move forward. */
    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
