package com.ridebooking.booking.application;

import java.util.regex.Pattern;

/**
 * Rules for the client-chosen Idempotency-Key. A random UUID is the usual choice. Restricting the alphabet
 * and length keeps the value safe to store, index and log.
 */
public final class IdempotencyKey {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private IdempotencyKey() {}

    /** @return the key unchanged, or null when the client sent none @throws IllegalArgumentException if malformed */
    public static String validated(String key) {
        if (key == null) {
            return null;
        }
        if (!VALID.matcher(key).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-64 characters: letters, digits, '-' or '_'");
        }
        return key;
    }
}
