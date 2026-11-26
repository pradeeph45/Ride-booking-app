package com.ridebooking.booking.application.matching;

import com.ridebooking.booking.domain.ServiceType;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/** Asks driver-service for nearby drivers over HTTP, authenticating with {@link ServiceTokenProvider}. */
@Component
public class RestDriverDirectory implements DriverDirectory {

    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final MatchingProperties props;

    public RestDriverDirectory(RestClient http, ServiceTokenProvider tokens, MatchingProperties props) {
        this.http = http;
        this.tokens = tokens;
        this.props = props;
    }

    @Override
    public List<NearbyDriver> nearby(ServiceType type, double lat, double lng, double radiusKm, int limit) {
        try {
            return query(type, lat, lng, radiusKm, limit);
        } catch (HttpClientErrorException.Unauthorized rejected) {
            tokens.invalidate(); // maybe the token went stale or keys rotated: get a fresh one and try ONCE more
            try {
                return query(type, lat, lng, radiusKm, limit);
            } catch (RestClientException second) {
                throw new DriverDirectoryException("driver-service rejected our credentials", second);
            }
        } catch (RestClientException e) {
            throw new DriverDirectoryException("driver-service is unavailable", e);
        }
    }

    private List<NearbyDriver> query(ServiceType type, double lat, double lng, double radiusKm, int limit) {
        String uri = UriComponentsBuilder.fromUriString(props.driverServiceUrl() + "/internal/drivers/nearby")
                .queryParam("lat", lat).queryParam("lng", lng).queryParam("type", type.name())
                .queryParam("radiusKm", radiusKm).queryParam("limit", limit)
                .build().toUriString();
        NearbyDriver[] body = http.get().uri(uri)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(NearbyDriver[].class);
        return body == null ? List.of() : Arrays.asList(body);
    }

    @SuppressWarnings("unused")
    private static final ParameterizedTypeReference<List<NearbyDriver>> TYPE = new ParameterizedTypeReference<>() {};
}
