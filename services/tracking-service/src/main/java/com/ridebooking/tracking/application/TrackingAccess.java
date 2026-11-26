package com.ridebooking.tracking.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the Redis entries written by booking-service (which trips are live and who is on them) and by
 * driver-service (each driver's last position). tracking-service never writes any of them and has no database.
 */
@Component
public class TrackingAccess {

    private final StringRedisTemplate redis;

    public TrackingAccess(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The live trip with this id, or empty if it is unknown, finished or cancelled. */
    public Optional<TrackingInfo> find(UUID bookingId) {
        String json = redis.opsForValue().get("tracking:booking:" + bookingId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> m = JsonParserFactory.getJsonParser().parseMap(json);
            return Optional.of(new TrackingInfo(UUID.fromString((String) m.get("riderId")),
                    UUID.fromString((String) m.get("driverId")), String.valueOf(m.get("status"))));
        } catch (RuntimeException corrupt) {
            return Optional.empty(); // an unreadable entry means no access, never an error page
        }
    }

    /** The trip this driver is currently driving, if any. */
    public Optional<UUID> activeBookingOf(UUID driverId) {
        String value = redis.opsForValue().get("tracking:driver:" + driverId);
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException corrupt) {
            return Optional.empty();
        }
    }

    public Optional<LocationSnapshot> lastLocation(UUID driverId) {
        String json = redis.opsForValue().get("driver:" + driverId + ":last");
        return json == null ? Optional.empty() : LocationSnapshot.parse(json);
    }
}
