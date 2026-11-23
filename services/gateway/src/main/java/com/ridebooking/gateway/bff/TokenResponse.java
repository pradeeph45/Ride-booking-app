package com.ridebooking.gateway.bff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The authorization server's token response. Internal: never serialized to the browser as-is. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") long expiresIn) {

    @Override
    public String toString() {
        return "TokenResponse[expiresIn=" + expiresIn + "]"; // tokens must never reach logs
    }
}
