package com.ridebooking.booking.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RatingStatsRepository extends JpaRepository<RatingStats, UUID> {

    /**
     * Adds one rating to the totals in a SINGLE atomic statement. Two ratings for the same person at the same
     * instant cannot overwrite each other, which a read-modify-write of the totals could.
     */
    @Modifying
    @Query(value = "INSERT INTO rating_stats (user_id, rating_count, rating_sum) VALUES (:user, 1, :score) "
            + "ON DUPLICATE KEY UPDATE rating_count = rating_count + 1, rating_sum = rating_sum + :score", nativeQuery = true)
    void add(@Param("user") UUID user, @Param("score") int score);
}
