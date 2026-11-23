package com.ridebooking.gateway.bff;

/** Wrong or expired OTP, bad refresh token, or rejected client. One message, no detail. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
