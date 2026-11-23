package com.ridebooking.gateway.bff;

/** What the browser gets back: a short-lived access token only. The refresh token stays in a cookie. */
public record SessionResponse(String accessToken, long expiresIn) {

    static SessionResponse from(TokenResponse t) {
        return new SessionResponse(t.accessToken(), t.expiresIn());
    }
}
