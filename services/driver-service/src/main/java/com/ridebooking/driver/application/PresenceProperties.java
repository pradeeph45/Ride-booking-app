package com.ridebooking.driver.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.presence")
public record PresenceProperties(
        /* a driver silent for this long is no longer offered rides */
        @DefaultValue("30s") Duration heartbeatTimeout,
        @DefaultValue("10s") Duration sweepInterval,
        /* location updates faster than this are refused with 429, which protects Redis from a runaway client */
        @DefaultValue("1s") Duration minPingInterval,
        /* false in tests so the sweep can be triggered by hand */
        @DefaultValue("true") boolean sweeper) {}
