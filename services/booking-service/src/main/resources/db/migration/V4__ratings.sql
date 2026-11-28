-- Ratings: after a COMPLETED trip each side (rider, driver) can rate the other once, 1 to 5 stars.

CREATE TABLE ratings (
    id         BINARY(16)   NOT NULL,
    booking_id BINARY(16)   NOT NULL,
    rater_id   BINARY(16)   NOT NULL,   -- auth-service user ids; no FK across services
    ratee_id   BINARY(16)   NOT NULL,
    score      INT          NOT NULL,
    comment    VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_rating_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    -- the real guard: one rating per person per trip, however many requests race
    CONSTRAINT uk_rating_booking_rater UNIQUE (booking_id, rater_id),
    CONSTRAINT ck_rating_score CHECK (score BETWEEN 1 AND 5),
    INDEX idx_rating_ratee (ratee_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Running totals per user so the average is one primary-key read, however many ratings there are.
-- Updated in the same transaction as the rating, with an atomic upsert (see RatingStatsRepository).
CREATE TABLE rating_stats (
    user_id      BINARY(16) NOT NULL,
    rating_count INT        NOT NULL,
    rating_sum   INT        NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
