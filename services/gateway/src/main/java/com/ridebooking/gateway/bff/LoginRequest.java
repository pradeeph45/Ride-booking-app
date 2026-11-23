package com.ridebooking.gateway.bff;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "\\d{4,10}") String otp) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]"; // the code is a credential
    }
}
