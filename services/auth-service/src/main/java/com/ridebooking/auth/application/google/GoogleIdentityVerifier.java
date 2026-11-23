package com.ridebooking.auth.application.google;

/** Port: turns a raw Google ID token into a trusted identity, or fails. */
public interface GoogleIdentityVerifier {

    /** @throws InvalidGoogleTokenException for any reason the token cannot be trusted */
    GoogleIdentity verify(String idToken);
}
