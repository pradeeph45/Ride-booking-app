package com.ridebooking.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Read-only view of a user's running totals. Written only through {@link RatingStatsRepository#add}. */
@Entity
@Table(name = "rating_stats")
public class RatingStats {

    @Id
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "rating_count", nullable = false, insertable = false, updatable = false)
    private int count;

    @Column(name = "rating_sum", nullable = false, insertable = false, updatable = false)
    private int sum;

    protected RatingStats() {
        // for JPA
    }

    public int getCount() { return count; }

    /** Average to two decimals, or null when nobody has rated this user yet. */
    public BigDecimal average() {
        return count == 0 ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
