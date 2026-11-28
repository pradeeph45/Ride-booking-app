package com.ridebooking.booking.application.metering;

import com.ridebooking.booking.application.metering.TripMeterMath.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Adds up the distance a driver really covers between "start" and "complete", from the GPS pings that
 * driver-service already publishes. One Redis hash per trip: {@code meter:{bookingId}}.
 *
 * <p>Every booking-service instance hears every ping, so two instances will often try to count the same one. Each
 * update is therefore a compare-and-set on "the newest ping time looked at" (done atomically in Redis), and an
 * instance that loses simply re-reads and finds the ping already counted. A ping is never counted twice and a
 * ping that arrives late is never counted at all.
 *
 * <p>Best-effort by design: if Redis loses the meter, the trip is completed at its estimate instead.
 */
@Component
public class TripMeter {

    private static final Logger log = LoggerFactory.getLogger(TripMeter.class);
    private static final int ATTEMPTS = 4;

    /** Creates the meter only if there is none, so a repeated "start" cannot reset a trip in progress. */
    private static final DefaultRedisScript<Long> BEGIN = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "redis.call('hset', KEYS[1], 'lat', '0', 'lng', '0', 'ts', '0', 'lastSeen', '0', 'meters', '0', "
                    + "'steps', '0', 'glitches', '0', 'startedAt', ARGV[1]) "
                    + "redis.call('expire', KEYS[1], ARGV[2]) return 1", Long.class);

    /** Writes the new state only if nobody changed it since it was read; -1 if the trip has no meter any more. */
    private static final DefaultRedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then return -1 end "
                    + "if redis.call('hget', KEYS[1], 'lastSeen') ~= ARGV[1] then return 0 end "
                    + "redis.call('hset', KEYS[1], 'lat', ARGV[2], 'lng', ARGV[3], 'ts', ARGV[4], 'lastSeen', ARGV[5], "
                    + "'meters', ARGV[6], 'steps', ARGV[7], 'glitches', ARGV[8]) "
                    + "redis.call('expire', KEYS[1], ARGV[9]) return 1", Long.class);

    public record Reading(double meters, int steps, long startedAt) { }

    private final StringRedisTemplate redis;
    private final MeteringProperties props;

    public TripMeter(StringRedisTemplate redis, MeteringProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** The trip has started: begin measuring. Safe to call again. */
    public void begin(UUID bookingId, Instant startedAt) {
        redis.execute(BEGIN, List.of(key(bookingId)), Long.toString(startedAt.toEpochMilli()), ttlSeconds());
    }

    /** Handles one raw message from the driver-locations channel. */
    public Optional<Verdict> record(String pingJson) {
        return DriverPing.parse(pingJson).flatMap(this::record);
    }

    /**
     * Counts a ping towards the driver's trip, if the driver is on a STARTED one.
     *
     * @return what was decided, or empty when the ping is not part of any metered trip
     *         (driver not on a trip, trip not started yet, or no meter)
     */
    public Optional<Verdict> record(DriverPing ping) {
        String bookingId = redis.opsForValue().get("tracking:driver:" + ping.driverId());
        if (bookingId == null) {
            return Optional.empty(); // the driver is not on a trip
        }
        String info = redis.opsForValue().get("tracking:booking:" + bookingId);
        if (info == null || !"STARTED".equals(JsonParserFactory.getJsonParser().parseMap(info).get("status"))) {
            return Optional.empty(); // on the way to the pickup, or already finished: only the trip itself is metered
        }
        String key = "meter:" + bookingId;

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            MeterState before = load(key);
            if (before == null) {
                return Optional.empty();
            }
            TripMeterMath.Step step = TripMeterMath.apply(before, ping, props);
            if (!step.verdict().changesState()) {
                return Optional.of(step.verdict());
            }
            MeterState s = step.state();
            Long written = redis.execute(COMPARE_AND_SET, List.of(key), Long.toString(before.lastSeenTs()),
                    Double.toString(s.anchorLat()), Double.toString(s.anchorLng()), Long.toString(s.anchorTs()),
                    Long.toString(s.lastSeenTs()), Double.toString(s.meters()), Integer.toString(s.steps()),
                    Integer.toString(s.glitches()), ttlSeconds());
            if (written == null || written == -1L) {
                return Optional.empty();
            }
            if (written == 1L) {
                return Optional.of(step.verdict());
            }
            // someone else updated the meter between our read and our write: read again and re-decide
        }
        log.warn("Could not update the meter for booking {} after {} attempts; the ping is skipped", bookingId, ATTEMPTS);
        return Optional.empty();
    }

    /** What has been measured so far, or empty if this trip has no meter. */
    public Optional<Reading> reading(UUID bookingId) {
        MeterState s = load(key(bookingId));
        return s == null ? Optional.empty() : Optional.of(new Reading(s.meters(), s.steps(), s.startedAt()));
    }

    /** The trip is over: forget its meter. */
    public void discard(UUID bookingId) {
        try {
            redis.delete(key(bookingId));
        } catch (RuntimeException e) {
            log.warn("Could not clear the meter for booking {}; it expires on its own", bookingId, e);
        }
    }

    private MeterState load(String key) {
        Map<Object, Object> h = redis.opsForHash().entries(key);
        if (h.isEmpty()) {
            return null;
        }
        return new MeterState(d(h, "lat"), d(h, "lng"), l(h, "ts"), l(h, "lastSeen"), d(h, "meters"),
                (int) l(h, "steps"), (int) l(h, "glitches"), l(h, "startedAt"));
    }

    private static double d(Map<Object, Object> h, String field) {
        return Double.parseDouble(String.valueOf(h.get(field)));
    }

    private static long l(Map<Object, Object> h, String field) {
        return Long.parseLong(String.valueOf(h.get(field)));
    }

    private String ttlSeconds() {
        return Long.toString(props.stateTtl().toSeconds());
    }

    private static String key(UUID bookingId) {
        return "meter:" + bookingId;
    }
}
