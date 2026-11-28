package com.ridebooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RatingTest {

    final UUID trip = UUID.randomUUID();
    final UUID rider = UUID.randomUUID();
    final UUID driver = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-28T10:00:00Z");

    @Test
    void scoresOutsideOneToFiveAreRefused() {
        for (int bad : new int[] {0, -1, 6, 100}) {
            assertThatThrownBy(() -> Rating.of(trip, rider, driver, bad, null, now)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int good = 1; good <= 5; good++) {
            assertThat(Rating.of(trip, rider, driver, good, null, now).getScore()).isEqualTo(good);
        }
    }

    @Test
    void nobodyCanRateThemselves() {
        assertThatThrownBy(() -> Rating.of(trip, rider, rider, 5, null, now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commentsAreTrimmedAndEmptyMeansNoComment() {
        assertThat(Rating.of(trip, rider, driver, 5, "  Lovely ride \n", now).getComment()).isEqualTo("Lovely ride");
        assertThat(Rating.of(trip, rider, driver, 5, "   ", now).getComment()).isNull();
        assertThat(Rating.of(trip, rider, driver, 5, null, now).getComment()).isNull();
    }

    @Test
    void aTooLongCommentIsRefusedNotSilentlyCut() {
        assertThat(Rating.of(trip, rider, driver, 5, "x".repeat(500), now).getComment()).hasSize(500);
        assertThatThrownBy(() -> Rating.of(trip, rider, driver, 5, "x".repeat(501), now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRepeatIsRecognisedEvenWithDifferentWhitespace() {
        Rating r = Rating.of(trip, rider, driver, 4, "Good", now);

        assertThat(r.saysTheSameAs(4, "  Good ")).isTrue();
        assertThat(r.saysTheSameAs(5, "Good")).isFalse();
        assertThat(r.saysTheSameAs(4, "Better")).isFalse();
        assertThat(r.saysTheSameAs(4, null)).isFalse();
        assertThat(Rating.of(trip, rider, driver, 4, null, now).saysTheSameAs(4, "  ")).isTrue(); // blank equals none
    }
}
