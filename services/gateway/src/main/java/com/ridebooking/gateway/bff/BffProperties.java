package com.ridebooking.gateway.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Backend-for-frontend settings. The client secret lives here, server-side, never in the browser. */
@ConfigurationProperties("ridebooking.gateway")
public record BffProperties(
        String authServiceUrl,
        String clientId,
        String clientSecret,
        /* true behind HTTPS; false only for plain-http localhost development */
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("rb_refresh") String refreshCookieName,
        String allowedOrigin) {}
