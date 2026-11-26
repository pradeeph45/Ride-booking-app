package com.ridebooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RideOfferTest {

    static final Instant T = Instant.parse("2026-06-01T10:00:00Z");
    static final Duration TTL = Duration.ofSeconds(15);

    private RideOffer offer() {
        return RideOffer.offer(UUID.randomUUID(), UUID.randomUUID(), 1.234, T, TTL);
    }

    @Test
    void newOfferIsPendingWithADeadline() {
        RideOffer o = offer();

        assertThat(o.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(o.getExpiresAt()).isEqualTo(T.plusSeconds(15));
        assertThat(o.getPickupDistanceKm()).isEqualByComparingTo("1.23");
    }

    @Test
    void canBeAcceptedInTimeAndRepeatingItIsANoOp() {
        RideOffer o = offer();

        assertThat(o.accept(T.plusSeconds(5))).isTrue();
        assertThat(o.accept(T.plusSeconds(40))).isFalse();         // a retry, even after the deadline: still "accepted"
        assertThat(o.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void cannotBeAcceptedAtOrAfterTheDeadlineEvenIfTheExpiryJobHasNotRunYet() {
        RideOffer o = offer();

        assertThatThrownBy(() -> o.accept(T.plusSeconds(15))).isInstanceOfSatisfying(OfferNotActiveException.class, e -> assertThat(e.isExpired()).isTrue());
        assertThat(o.getStatus()).isEqualTo(OfferStatus.PENDING);      // state untouched; the sweep will expire it
        assertThat(o.accept(T.plusSeconds(14))).isTrue();              // one second earlier was fine
    }

    @Test
    void rejectingEndsTheOfferAndRepeatingIsANoOp() {
        RideOffer o = offer();

        assertThat(o.reject(T.plusSeconds(3))).isTrue();
        assertThat(o.reject(T.plusSeconds(4))).isFalse();
        assertThatThrownBy(() -> o.accept(T.plusSeconds(5))).isInstanceOf(OfferNotActiveException.class);
    }

    @Test
    void expiryOnlyHappensAfterTheDeadlineAndOnlyToPendingOffers() {
        RideOffer o = offer();

        assertThat(o.expire(T.plusSeconds(14))).isFalse();             // not yet
        assertThat(o.expire(T.plusSeconds(15))).isTrue();
        assertThat(o.getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(o.expire(T.plusSeconds(99))).isFalse();             // already done

        RideOffer accepted = offer();
        accepted.accept(T);
        assertThat(accepted.expire(T.plusSeconds(99))).isFalse();      // an accepted offer is never expired
        assertThat(accepted.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void anExpiredOfferCannotBeAcceptedOrRejectedAndSaysItExpired() {
        RideOffer o = offer();
        o.expire(T.plusSeconds(20));

        assertThatThrownBy(() -> o.accept(T.plusSeconds(21))).isInstanceOfSatisfying(OfferNotActiveException.class, e -> assertThat(e.isExpired()).isTrue());
        assertThatThrownBy(() -> o.reject(T.plusSeconds(21))).isInstanceOf(OfferNotActiveException.class);
    }

    @Test
    void cancellingOnlyAffectsPendingOffers() {
        RideOffer pending = offer();
        assertThat(pending.cancel(T)).isTrue();
        assertThat(pending.getStatus()).isEqualTo(OfferStatus.CANCELLED);

        RideOffer accepted = offer();
        accepted.accept(T);
        assertThat(accepted.cancel(T)).isFalse();                      // the booking itself handles an accepted ride
        assertThat(accepted.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }
}
