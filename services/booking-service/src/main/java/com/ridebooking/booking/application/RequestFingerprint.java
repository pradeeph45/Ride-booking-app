package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.Place;
import com.ridebooking.booking.domain.ServiceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A digest of what the client asked for. It is stored next to the Idempotency-Key so the same key can be
 * recognised as a retry (same fingerprint) or as a bug / abuse (same key, different request).
 * Fields are length-prefixed so different requests cannot be made to produce the same input text.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String of(ServiceType type, Place pickup, Place drop) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, type.name());
        append(canonical, pickup);
        append(canonical, drop);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(StringBuilder out, Place p) {
        append(out, Double.toString(p.lat()));
        append(out, Double.toString(p.lng()));
        append(out, p.address() == null ? "\u0000null" : p.address());
    }

    private static void append(StringBuilder out, String field) {
        out.append(field.length()).append(':').append(field).append(';');
    }
}
