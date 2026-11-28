package com.ridebooking.booking.application;

import com.ridebooking.booking.domain.AlreadyRatedException;
import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingNotFoundException;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.BookingStatus;
import com.ridebooking.booking.domain.Rating;
import com.ridebooking.booking.domain.RatingNotAllowedException;
import com.ridebooking.booking.domain.RatingRepository;
import com.ridebooking.booking.domain.RatingStatsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rider and driver rate each other once per completed trip. The unique index on (booking, rater) is the real
 * guard against double submits: if two requests race, one insert wins and the other re-reads the winner's rating,
 * so a double tap is harmless but a different second answer is refused.
 */
@Service
public class RatingService {

    private final BookingRepository bookings;
    private final RatingRepository ratings;
    private final RatingStatsRepository stats;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RatingService(BookingRepository bookings, RatingRepository ratings, RatingStatsRepository stats,
                         Clock clock, TransactionTemplate tx) {
        this.bookings = bookings;
        this.ratings = ratings;
        this.stats = stats;
        this.clock = clock;
        this.tx = tx;
    }

    public record Outcome(Rating rating, boolean replayed) { }

    public record Summary(BigDecimal average, int count) { }

    public Outcome rate(UUID bookingId, CurrentUser user, int score, String comment) {
        String cleaned = Rating.normalise(comment); // refuse over-long comments before touching the database
        try {
            return tx.execute(status -> rateOnce(bookingId, user, score, cleaned));
        } catch (DataIntegrityViolationException | ConcurrencyFailureException lostRace) {
            // The other request won. Its insert poisoned ours, so look at what it stored in a fresh transaction.
            return tx.execute(status -> existing(bookingId, user.id(), score, cleaned).orElseThrow(() -> lostRace));
        }
    }

    private Outcome rateOnce(UUID bookingId, CurrentUser user, int score, String comment) {
        Booking booking = bookings.findById(bookingId).filter(b -> b.involves(user.id())).orElseThrow(BookingNotFoundException::new);
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new RatingNotAllowedException();
        }
        Optional<Outcome> already = existing(bookingId, user.id(), score, comment);
        if (already.isPresent()) {
            return already.get();
        }
        UUID ratee = user.id().equals(booking.getRiderId()) ? booking.getDriverId() : booking.getRiderId();
        Rating saved = ratings.saveAndFlush(Rating.of(bookingId, user.id(), ratee, score, comment, clock.instant()));
        stats.add(ratee, score); // same transaction: the rating and the totals appear together or not at all
        return new Outcome(saved, false);
    }

    private Optional<Outcome> existing(UUID bookingId, UUID raterId, int score, String comment) {
        return ratings.findByBookingIdAndRaterId(bookingId, raterId).map(r -> {
            if (!r.saysTheSameAs(score, comment)) {
                throw new AlreadyRatedException();
            }
            return new Outcome(r, true);
        });
    }

    /** The caller's own rating of this trip, if they gave one. Trips you were not on look like they do not exist. */
    @Transactional(readOnly = true)
    public Optional<Rating> mine(UUID bookingId, CurrentUser user) {
        bookings.findById(bookingId).filter(b -> b.involves(user.id())).orElseThrow(BookingNotFoundException::new);
        return ratings.findByBookingIdAndRaterId(bookingId, user.id());
    }

    /** How others rate the caller: only the average and the count, never who said what. */
    @Transactional(readOnly = true)
    public Summary summaryFor(CurrentUser user) {
        return stats.findById(user.id()).map(s -> new Summary(s.average(), s.getCount())).orElse(new Summary(null, 0));
    }
}
