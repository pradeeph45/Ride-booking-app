package com.ridebooking.booking.application.metering;

/**
 * The rules for turning a stream of noisy GPS pings into a trustworthy distance. Pure: no Redis, no clock, so
 * every rule is tested directly.
 *
 * <ul>
 *   <li>a ping not newer than the last one looked at is a duplicate or arrived out of order: ignored;</li>
 *   <li>a hop under the wobble threshold is a stationary phone: ignored, and the anchor stays so slow drift still adds up;</li>
 *   <li>a hop implying an impossible speed is a GPS glitch: not counted and the anchor stays, so one wild point does
 *       not poison the next good one; after several in a row the new position is trusted instead;</li>
 *   <li>everything else is real driving: the straight-line hop is added (a lower bound, so never unfair to the rider).</li>
 * </ul>
 */
public final class TripMeterMath {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public enum Verdict {
        /** The first position after the trip started: nothing to measure yet. */
        ANCHORED,
        /** A real hop, added to the distance. */
        COUNTED,
        /** Impossible speed: not counted. */
        GLITCH,
        /** Too many glitches in a row, so the new position became the anchor without adding distance. */
        RE_ANCHORED,
        /** Smaller than GPS wobble: not counted. */
        WOBBLE,
        /** Not newer than the last ping looked at. */
        DUPLICATE,
        /** Not a position on Earth. */
        INVALID;

        /** Whether the meter state has to be written back. */
        public boolean changesState() {
            return this == ANCHORED || this == COUNTED || this == GLITCH || this == RE_ANCHORED;
        }
    }

    public record Step(MeterState state, Verdict verdict) { }

    private TripMeterMath() {}

    public static Step apply(MeterState s, DriverPing p, MeteringProperties limits) {
        if (!Double.isFinite(p.lat()) || !Double.isFinite(p.lng()) || Math.abs(p.lat()) > 90 || Math.abs(p.lng()) > 180 || p.ts() <= 0) {
            return new Step(s, Verdict.INVALID);
        }
        if (p.ts() <= s.lastSeenTs()) {
            return new Step(s, Verdict.DUPLICATE);
        }
        if (!s.hasAnchor()) {
            return new Step(new MeterState(p.lat(), p.lng(), p.ts(), p.ts(), s.meters(), s.steps(), 0, s.startedAt()), Verdict.ANCHORED);
        }

        double hop = distanceMeters(s.anchorLat(), s.anchorLng(), p.lat(), p.lng());
        if (hop < limits.minMoveMeters()) {
            return new Step(s, Verdict.WOBBLE);
        }

        double seconds = (p.ts() - s.anchorTs()) / 1000.0;
        double speedKmh = hop / seconds * 3.6;
        if (speedKmh > limits.maxSpeedKmh()) {
            int glitches = s.glitches() + 1;
            if (glitches >= limits.maxConsecutiveJumps()) {
                return new Step(new MeterState(p.lat(), p.lng(), p.ts(), p.ts(), s.meters(), s.steps(), 0, s.startedAt()), Verdict.RE_ANCHORED);
            }
            return new Step(new MeterState(s.anchorLat(), s.anchorLng(), s.anchorTs(), p.ts(), s.meters(), s.steps(), glitches, s.startedAt()), Verdict.GLITCH);
        }

        return new Step(new MeterState(p.lat(), p.lng(), p.ts(), p.ts(), s.meters() + hop, s.steps() + 1, 0, s.startedAt()), Verdict.COUNTED);
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }
}
