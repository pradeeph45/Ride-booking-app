package com.ridebooking.booking.application.metering;

/**
 * Where a trip meter stands.
 *
 * @param anchorLat,anchorLng,anchorTs the last position that was accepted; distance is measured from here
 * @param lastSeenTs   the newest ping time looked at (counted or glitch), so a ping delivered twice is not counted twice
 * @param meters       distance accumulated so far
 * @param steps        how many hops have been counted (a trip needs at least two to be metered)
 * @param glitches     consecutive hops rejected as impossible
 * @param startedAt    when the trip started (epoch millis, booking clock)
 */
public record MeterState(double anchorLat, double anchorLng, long anchorTs, long lastSeenTs,
                         double meters, int steps, int glitches, long startedAt) {

    public static MeterState started(long startedAt) {
        return new MeterState(0, 0, 0, 0, 0, 0, 0, startedAt);
    }

    public boolean hasAnchor() {
        return anchorTs > 0;
    }
}
