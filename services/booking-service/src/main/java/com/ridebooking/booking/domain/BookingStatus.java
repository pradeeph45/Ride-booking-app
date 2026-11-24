package com.ridebooking.booking.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a booking. This table is the single source of truth for which moves are legal, so
 * no other code needs its own "if status == ..." rules.
 *
 * <pre>
 * REQUESTED -> MATCHED -> ARRIVED -> STARTED -> COMPLETED
 *     \           \          \
 *      +-----------+----------+--> CANCELLED   (rider, any time before the trip starts)
 * </pre>
 */
public enum BookingStatus {
    REQUESTED,
    MATCHED,
    ARRIVED,
    STARTED,
    COMPLETED,
    CANCELLED;

    private static final Map<BookingStatus, Set<BookingStatus>> NEXT = new EnumMap<>(BookingStatus.class);

    static {
        NEXT.put(REQUESTED, EnumSet.of(MATCHED, CANCELLED));
        NEXT.put(MATCHED, EnumSet.of(ARRIVED, CANCELLED));
        NEXT.put(ARRIVED, EnumSet.of(STARTED, CANCELLED));
        NEXT.put(STARTED, EnumSet.of(COMPLETED));
        NEXT.put(COMPLETED, EnumSet.noneOf(BookingStatus.class));
        NEXT.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
    }

    public boolean canMoveTo(BookingStatus next) {
        return NEXT.get(this).contains(next);
    }

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }
}
