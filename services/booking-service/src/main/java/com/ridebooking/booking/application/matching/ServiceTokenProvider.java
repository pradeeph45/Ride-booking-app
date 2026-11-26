package com.ridebooking.booking.application.matching;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * booking-service's own identity when it calls other services: an OAuth2 client-credentials token for the
 * "drivers.read" scope, cached until just before it expires. No user is involved, so no user data can leak
 * into these calls, and the token can do nothing except read the driver directory.
 */
@Component
public class ServiceTokenProvider {

    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(60); // refresh a minute early, never use a dying token

    private final RestClient http;
    private final MatchingProperties props;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.MIN;

    public ServiceTokenProvider(RestClient http, MatchingProperties props, Clock clock) {
        this.http = http;
        this.props = props;
        this.clock = clock;
    }

    /** One caller refreshes while the others wait, so a burst of requests causes a single token request. */
    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(refreshAt)) {
            refresh();
        }
        return token;
    }

    /** Forget the cached token, e.g. after the receiving service rejected it. */
    public synchronized void invalidate() {
        token = null;
        refreshAt = Instant.MIN;
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "drivers.read");
        try {
            Map<String, Object> body = http.post().uri(props.authUrl() + "/oauth2/token")
                    .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(Map.class);
            String value = body == null ? null : (String) body.get("access_token");
            if (value == null) {
                throw new DriverDirectoryException("Authorization server returned no token");
            }
            long seconds = body.get("expires_in") instanceof Number n ? n.longValue() : 300;
            token = value;
            refreshAt = clock.instant().plusSeconds(seconds).minus(SAFETY_MARGIN);
        } catch (RestClientException e) {
            invalidate(); // never keep a half-valid state
            throw new DriverDirectoryException("Could not obtain a service token", e);
        }
    }
}
