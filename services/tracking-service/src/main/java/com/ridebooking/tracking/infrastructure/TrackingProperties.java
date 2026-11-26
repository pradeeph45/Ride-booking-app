package com.ridebooking.tracking.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ridebooking.tracking")
public record TrackingProperties(String allowedOrigin) {}
