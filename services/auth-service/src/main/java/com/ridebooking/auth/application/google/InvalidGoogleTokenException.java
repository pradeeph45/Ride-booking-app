package com.ridebooking.auth.application.google;

/** The ID token is forged, expired, for another app, unverified, or Google login is not configured. */
public class InvalidGoogleTokenException extends RuntimeException {

    public InvalidGoogleTokenException(String reason) {
        super(reason);
    }

    public InvalidGoogleTokenException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
