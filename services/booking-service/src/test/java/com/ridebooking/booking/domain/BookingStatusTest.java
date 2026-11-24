package com.ridebooking.booking.domain;

import static com.ridebooking.booking.domain.BookingStatus.ARRIVED;
import static com.ridebooking.booking.domain.BookingStatus.CANCELLED;
import static com.ridebooking.booking.domain.BookingStatus.COMPLETED;
import static com.ridebooking.booking.domain.BookingStatus.MATCHED;
import static com.ridebooking.booking.domain.BookingStatus.REQUESTED;
import static com.ridebooking.booking.domain.BookingStatus.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BookingStatusTest {

    @Test
    void happyPathMovesForwardOneStepAtATime() {
        assertThat(REQUESTED.canMoveTo(MATCHED)).isTrue();
        assertThat(MATCHED.canMoveTo(ARRIVED)).isTrue();
        assertThat(ARRIVED.canMoveTo(STARTED)).isTrue();
        assertThat(STARTED.canMoveTo(COMPLETED)).isTrue();
    }

    @Test
    void stepsCannotBeSkippedOrReversed() {
        assertThat(REQUESTED.canMoveTo(STARTED)).isFalse();
        assertThat(MATCHED.canMoveTo(COMPLETED)).isFalse();
        assertThat(STARTED.canMoveTo(MATCHED)).isFalse();
        assertThat(ARRIVED.canMoveTo(REQUESTED)).isFalse();
    }

    @Test
    void cancellationIsPossibleOnlyBeforeTheTripStarts() {
        assertThat(List.of(REQUESTED, MATCHED, ARRIVED)).allMatch(s -> s.canMoveTo(CANCELLED));
        assertThat(STARTED.canMoveTo(CANCELLED)).isFalse();
    }

    @Test
    void terminalStatesGoNowhere() {
        for (BookingStatus terminal : List.of(COMPLETED, CANCELLED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (BookingStatus any : BookingStatus.values()) {
                assertThat(terminal.canMoveTo(any)).isFalse();
            }
        }
    }

    @Test
    void activeStatesAreTheNonTerminalOnes() {
        assertThat(BookingStatus.values()).filteredOn(s -> !s.isTerminal())
                .containsExactly(REQUESTED, MATCHED, ARRIVED, STARTED);
    }
}
