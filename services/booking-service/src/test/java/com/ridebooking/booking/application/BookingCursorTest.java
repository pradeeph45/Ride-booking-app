package com.ridebooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingCursorTest {

    @Test
    void roundTripsWithMicrosecondPrecision() {
        BookingCursor cursor = new BookingCursor(Instant.parse("2026-06-01T10:00:00.123456Z"), UUID.randomUUID());

        assertThat(BookingCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void isUrlSafeOpaqueText() {
        String encoded = new BookingCursor(Instant.now(), UUID.randomUUID()).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void garbageIsRejectedAsABadRequestNotAServerError() {
        assertThatThrownBy(() -> BookingCursor.decode("not-a-cursor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BookingCursor.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BookingCursor.decode("%%%")).isInstanceOf(IllegalArgumentException.class);
    }
}
