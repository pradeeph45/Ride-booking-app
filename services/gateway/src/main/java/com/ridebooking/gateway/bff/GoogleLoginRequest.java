package com.ridebooking.gateway.bff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The ID token from Google's button, plus the role to use if this turns out to be a first-time signup. */
public record GoogleLoginRequest(
        @NotBlank @Size(max = 8192) String idToken,
        @Pattern(regexp = "RIDER|DRIVER") String role) {

    @Override
    public String toString() {
        return "GoogleLoginRequest[role=" + role + "]"; // the ID token is a credential
    }
}
