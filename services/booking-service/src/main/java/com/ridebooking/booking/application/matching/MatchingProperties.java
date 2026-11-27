package com.ridebooking.booking.application.matching;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.matching")
public record MatchingProperties(
        /* false in tests: the dispatcher is then run by hand */
        @DefaultValue("true") boolean enabled,
        /* how often the dispatcher looks for rides that need a driver */
        @DefaultValue("2s") Duration tick,
        /* how long a driver has to answer an offer */
        @DefaultValue("15s") Duration offerTtl,
        @DefaultValue("8") double radiusKm,
        @DefaultValue("10") int candidateLimit,
        /* wait this long before searching again for a ride nobody could take */
        @DefaultValue("5s") Duration retryDelay,
        /* a ride nobody accepted within this long is closed as EXPIRED ("no driver found") */
        @DefaultValue("3m") Duration unmatchedTimeout,
        @DefaultValue("http://localhost:8083") String driverServiceUrl,
        @DefaultValue("http://localhost:8081") String authUrl,
        @DefaultValue("rb-booking") String clientId,
        String clientSecret) {

    @Override
    public String toString() {
        return "MatchingProperties[enabled=" + enabled + ", offerTtl=" + offerTtl + "]"; // never print the client secret
    }
}
