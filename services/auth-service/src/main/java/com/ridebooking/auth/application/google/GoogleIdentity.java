package com.ridebooking.auth.application.google;

/** What a verified Google ID token tells us. {@code subject} is Google's stable account id. */
public record GoogleIdentity(String subject, String email) {}
