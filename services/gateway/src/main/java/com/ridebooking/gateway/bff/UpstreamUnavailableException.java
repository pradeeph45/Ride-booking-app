package com.ridebooking.gateway.bff;

public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(Throwable cause) {
        super("Authentication service unavailable", cause);
    }
}
