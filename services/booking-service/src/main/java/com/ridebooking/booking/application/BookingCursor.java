package com.ridebooking.booking.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * "Where the last page ended" for keyset pagination: (createdAt, id) of the last row returned.
 * The next page is "rows strictly before this point", so it stays correct and fast even as new
 * bookings arrive, unlike OFFSET which skips or repeats rows and gets slower on deep pages.
 *
 * <p>The cursor is not a secret and needs no signature: every query is already restricted to the
 * caller's own bookings, so a forged cursor can only choose a different starting position in their own list.
 */
public record BookingCursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static BookingCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            return new BookingCursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
