package com.ridebooking.auth.application.otp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.otp")
public record OtpProperties(
        @DefaultValue("6") int length,
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("60s") Duration resendCooldown) {}
