package com.ridebooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridebooking.booking.application.FareQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingTest {

    static final Instant T = Instant.parse("2026-06-01T10:00:00Z");
    final UUID rider = UUID.randomUUID();
    final UUID driver = UUID.randomUUID();

    private Booking booking() {
        FareQuote quote = new FareQuote(ServiceType.CAR_MINI, new BigDecimal("6.40"), 16, new BigDecimal("148.50"), "INR");
        return Booking.request(rider, ServiceType.CAR_MINI, Place.of(12.9756, 77.6050, "A"),
                Place.of(12.9352, 77.6245, "B"), quote, T);
    }

    @Test
    void newBookingIsRequestedWithTheServerComputedFare() {
        Booking b = booking();

        assertThat(b.getStatus()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(b.getEstimatedFare()).isEqualByComparingTo("148.50");
        assertThat(b.getDriverId()).isNull();
        assertThat(b.getRiderId()).isEqualTo(rider);
    }

    @Test
    void fullTripFollowsTheStateMachine() {
        Booking b = booking();

        b.assignDriver(driver, T.plusSeconds(10));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.MATCHED);
        assertThat(b.getDriverId()).isEqualTo(driver);

        b.driverArrived(driver, T.plusSeconds(200));
        b.start(driver, T.plusSeconds(260));
        b.complete(driver, T.plusSeconds(1200));

        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(b.getFinalFare()).isEqualByComparingTo("148.50");
        assertThat(b.getUpdatedAt()).isEqualTo(T.plusSeconds(1200));
    }

    @Test
    void aBookingCanOnlyBeAcceptedOnce() {
        Booking b = booking();
        b.assignDriver(driver, T);

        assertThatThrownBy(() -> b.assignDriver(UUID.randomUUID(), T))
                .isInstanceOf(InvalidBookingTransitionException.class);
        assertThat(b.getDriverId()).isEqualTo(driver);
    }

    @Test
    void stepsCannotBeSkipped() {
        Booking b = booking();
        b.assignDriver(driver, T);

        assertThatThrownBy(() -> b.start(driver, T)).isInstanceOf(InvalidBookingTransitionException.class);
        assertThatThrownBy(() -> b.complete(driver, T)).isInstanceOf(InvalidBookingTransitionException.class);
    }

    @Test
    void onlyTheAssignedDriverMayProgressTheTrip() {
        Booking b = booking();
        b.assignDriver(driver, T);
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> b.driverArrived(other, T)).isInstanceOf(BookingAccessDeniedException.class);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.MATCHED);
    }

    @Test
    void riderCanCancelUntilTheTripStarts() {
        for (int stepsDone = 0; stepsDone <= 2; stepsDone++) {
            Booking b = booking();
            if (stepsDone >= 1) b.assignDriver(driver, T);
            if (stepsDone >= 2) b.driverArrived(driver, T);

            b.cancelByRider(rider, T.plusSeconds(5));

            assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }
    }

    @Test
    void cannotCancelOnceTheTripHasStartedOrFinished() {
        Booking started = booking();
        started.assignDriver(driver, T);
        started.driverArrived(driver, T);
        started.start(driver, T);
        assertThatThrownBy(() -> started.cancelByRider(rider, T)).isInstanceOf(InvalidBookingTransitionException.class);

        Booking done = booking();
        done.assignDriver(driver, T);
        done.driverArrived(driver, T);
        done.start(driver, T);
        done.complete(driver, T);
        assertThatThrownBy(() -> done.cancelByRider(rider, T)).isInstanceOf(InvalidBookingTransitionException.class);
    }

    @Test
    void anotherRiderCannotCancelMyBooking() {
        Booking b = booking();

        assertThatThrownBy(() -> b.cancelByRider(UUID.randomUUID(), T)).isInstanceOf(BookingAccessDeniedException.class);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.REQUESTED);
    }

    // ---- retry safety: repeating an action that already took effect is a harmless no-op -----------

    @Test
    void cancellingAnAlreadyCancelledBookingIsANoOpThatReportsNoChange() {
        Booking b = booking();
        assertThat(b.cancelByRider(rider, T)).isTrue();   // first time: changed

        assertThat(b.cancelByRider(rider, T.plusSeconds(60))).isFalse(); // retry: nothing changed
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getUpdatedAt()).isEqualTo(T);        // the retry did not touch the booking
    }

    @Test
    void theSameDriverAcceptingAgainIsANoOp() {
        Booking b = booking();
        assertThat(b.assignDriver(driver, T)).isTrue();

        assertThat(b.assignDriver(driver, T.plusSeconds(30))).isFalse();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.MATCHED);
        assertThat(b.getUpdatedAt()).isEqualTo(T);
    }

    @Test
    void aLateRetryOfAcceptStillSucceedsForTheDriverWhoOwnsTheTrip() {
        Booking b = booking();
        b.assignDriver(driver, T);
        b.driverArrived(driver, T);

        assertThat(b.assignDriver(driver, T)).isFalse(); // my trip, already further along: no error, no change
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ARRIVED);
    }

    @Test
    void acceptingACancelledBookingIsStillAnErrorEvenForItsFormerDriver() {
        Booking b = booking();
        b.assignDriver(driver, T);
        b.cancelByRider(rider, T);

        assertThatThrownBy(() -> b.assignDriver(driver, T)).isInstanceOf(InvalidBookingTransitionException.class);
    }

    @Test
    void repeatingArriveStartAndCompleteIsANoOp() {
        Booking b = booking();
        b.assignDriver(driver, T);
        assertThat(b.driverArrived(driver, T)).isTrue();
        assertThat(b.driverArrived(driver, T)).isFalse();
        assertThat(b.start(driver, T)).isTrue();
        assertThat(b.start(driver, T)).isFalse();
        assertThat(b.complete(driver, T)).isTrue();
        assertThat(b.complete(driver, T)).isFalse();

        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void retriesStillCheckWhoIsAsking() {
        Booking b = booking();
        b.assignDriver(driver, T);
        b.cancelByRider(rider, T);

        // a stranger cannot use "already cancelled" as a way to probe or to act
        assertThatThrownBy(() -> b.cancelByRider(UUID.randomUUID(), T)).isInstanceOf(BookingAccessDeniedException.class);
        assertThatThrownBy(() -> b.driverArrived(UUID.randomUUID(), T)).isInstanceOf(BookingAccessDeniedException.class);
    }

    @Test
    void aDifferentDriverStillCannotTakeABookingThatIsAlreadyTaken() {
        Booking b = booking();
        b.assignDriver(driver, T);

        assertThatThrownBy(() -> b.assignDriver(UUID.randomUUID(), T)).isInstanceOf(InvalidBookingTransitionException.class);
    }

    @Test
    void idempotencyKeyAndFingerprintAreRememberedOnTheBooking() {
        FareQuote quote = new FareQuote(ServiceType.BIKE, new BigDecimal("2.00"), 5, new BigDecimal("40.00"), "INR");
        Booking b = Booking.request(rider, ServiceType.BIKE, Place.of(12.9756, 77.6050, "A"), Place.of(12.9352, 77.6245, "B"),
                quote, T, "key-12345678", "f".repeat(64));

        assertThat(b.getIdempotencyKey()).isEqualTo("key-12345678");
        assertThat(b.getRequestFingerprint()).hasSize(64);
        assertThat(booking().getIdempotencyKey()).isNull(); // optional
    }

    @Test
    void aCancelledBookingCannotBeAccepted() {
        Booking b = booking();
        b.cancelByRider(rider, T);

        assertThatThrownBy(() -> b.assignDriver(driver, T)).isInstanceOf(InvalidBookingTransitionException.class);
    }

    @Test
    void aRiderCannotBeTheirOwnDriver() {
        Booking b = booking();

        assertThatThrownBy(() -> b.assignDriver(rider, T)).isInstanceOf(BookingAccessDeniedException.class);
    }
}
