package com.ridebooking.auth.application.session;

/** The session is revoked, expired, idle, or not the caller's. Deliberately gives no more detail. */
public class SessionInactiveException extends RuntimeException {

    public SessionInactiveException() {
        super("Session is not active");
    }
}
