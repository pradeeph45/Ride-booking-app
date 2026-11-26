package com.ridebooking.driver.application;

import com.ridebooking.driver.domain.VehicleType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

/**
 * Where the live drivers are. One Redis GEO set per vehicle type, so a search for a bike never scans cars,
 * plus a sorted set of last-heartbeat times so silent drivers can be excluded and swept away.
 *
 * <p>Redis cannot expire a single member of a GEO set, which is why the heartbeat set exists: reads check
 * it (so a dead phone is ignored immediately) and the sweeper deletes the leftovers.
 */
@Component
public class DriverGeoIndex {

    static final String GEO_PREFIX = "drivers:geo:";
    static final String ALIVE = "drivers:alive";

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final PresenceProperties props;

    public DriverGeoIndex(StringRedisTemplate redis, Clock clock, PresenceProperties props) {
        this.redis = redis;
        this.clock = clock;
        this.props = props;
    }

    /** Records a position and a heartbeat. GEOADD on an existing member just moves it. */
    public void upsert(VehicleType type, UUID driverId, double lat, double lng) {
        redis.opsForGeo().add(GEO_PREFIX + type, new Point(lng, lat), driverId.toString()); // Redis wants lng first
        redis.opsForZSet().add(ALIVE, driverId.toString(), clock.millis());
    }

    /** Removes the driver from every type's set (used when going offline or changing vehicle type). */
    public void remove(UUID driverId) {
        String member = driverId.toString();
        for (VehicleType t : VehicleType.values()) {
            redis.opsForGeo().remove(GEO_PREFIX + t, member);
        }
        redis.opsForZSet().remove(ALIVE, member);
    }

    /** Live drivers of {@code type} within {@code radiusKm}, nearest first. Silent drivers are excluded. */
    public List<NearbyDriver> nearby(VehicleType type, double lat, double lng, double radiusKm, int limit) {
        GeoResults<GeoLocation<String>> raw = redis.opsForGeo().search(
                GEO_PREFIX + type,
                GeoReference.fromCoordinate(lng, lat),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().sortAscending().limit(limit * 2L));
        if (raw == null) {
            return List.of();
        }
        long oldestAllowed = clock.millis() - props.heartbeatTimeout().toMillis();
        List<NearbyDriver> out = new ArrayList<>();
        for (GeoResult<GeoLocation<String>> r : raw) {
            Double lastSeen = redis.opsForZSet().score(ALIVE, r.getContent().getName());
            if (lastSeen == null || lastSeen < oldestAllowed) {
                continue; // heartbeat too old: do not offer rides to a dead phone
            }
            Point p = r.getContent().getPoint();
            out.add(new NearbyDriver(UUID.fromString(r.getContent().getName()), p.getY(), p.getX(), r.getDistance().getValue()));
            if (out.size() == limit) {
                break;
            }
        }
        return out;
    }

    /**
     * Deletes drivers whose heartbeat is older than the timeout. Safe to run on every instance at once:
     * removing something already removed does nothing.
     *
     * @return how many drivers were swept
     */
    public int sweepStale() {
        long cutoff = clock.millis() - props.heartbeatTimeout().toMillis();
        Set<String> stale = redis.opsForZSet().rangeByScore(ALIVE, Double.NEGATIVE_INFINITY, cutoff);
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String member : stale) {
            for (VehicleType t : VehicleType.values()) {
                redis.opsForGeo().remove(GEO_PREFIX + t, member);
            }
            Long gone = redis.opsForZSet().remove(ALIVE, member);
            removed += gone == null ? 0 : gone.intValue();
        }
        return removed;
    }
}
