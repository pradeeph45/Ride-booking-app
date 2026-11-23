package com.ridebooking.auth.application.google;

/** The identity is genuine but we will not log it in (disabled account, forbidden role). */
public class GoogleLoginRejectedException extends RuntimeException {

    public GoogleLoginRejectedException(String reason) {
        super(reason);
    }
}
