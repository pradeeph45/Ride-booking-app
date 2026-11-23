package com.ridebooking.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Inbound signup payload. toString is overridden so the password can never reach a log. */
public record UserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 72) String password, // 72 = BCrypt's input limit
        @NotNull SignupRole role) {

    @Override
    public String toString() {
        return "UserRequest[email=" + email + ", role=" + role + "]";
    }
}
