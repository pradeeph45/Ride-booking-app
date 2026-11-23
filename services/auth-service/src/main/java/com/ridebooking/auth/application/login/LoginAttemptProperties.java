package com.ridebooking.auth.application.login;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.login")
public record LoginAttemptProperties(
        @DefaultValue("5") int maxFailures,
        /* lock length; each new failure restarts the window */
        @DefaultValue("15m") Duration lockDuration) {}
