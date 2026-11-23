package com.ridebooking.auth.application.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.session")
public record SessionProperties(
        /* hard cap: after this the user must log in again, however active */
        @DefaultValue("30d") Duration absoluteTtl,
        /* no token refresh for this long ends the session */
        @DefaultValue("14d") Duration idleTtl,
        /* how long a revoked sid stays in Redis; must exceed the access-token lifetime (15m) */
        @DefaultValue("20m") Duration revocationTtl) {}
