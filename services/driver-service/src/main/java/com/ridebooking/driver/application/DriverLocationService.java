package com.ridebooking.driver.application;

import com.ridebooking.driver.domain.DriverOfflineException;
import com.ridebooking.driver.domain.DriverProfile;
import com.ridebooking.driver.domain.DriverProfileNotFoundException;
import com.ridebooking.driver.domain.DriverProfileRepository;
import com.ridebooking.driver.domain.DriverStatus;
import com.ridebooking.driver.domain.TooManyUpdatesException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Takes a driver's GPS ping: validates it, refuses runaway clients, updates the live index, and publishes the
 * position on the "driver-locations" Redis channel so other services (live tracking) can react.
 */
@Service
public class DriverLocationService {

    public static final String CHANNEL = "driver-locations";
    static final Duration LAST_POSITION_TTL = Duration.ofMinutes(2);

    private final DriverProfileRepository profiles;
    private final DriverGeoIndex index;
    private final StringRedisTemplate redis;
    private final PresenceProperties props;
    private final Clock clock;

    public DriverLocationService(DriverProfileRepository profiles, DriverGeoIndex index, StringRedisTemplate redis,
                                 PresenceProperties props, Clock clock) {
        this.profiles = profiles;
        this.index = index;
        this.redis = redis;
        this.props = props;
        this.clock = clock;
    }

    public void update(UUID driverId, double lat, double lng, Double heading) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Coordinates are outside the Earth's range");
        }
        if (heading != null && (!Double.isFinite(heading) || heading < 0 || heading > 360)) {
            throw new IllegalArgumentException("Heading must be between 0 and 360 degrees");
        }
        DriverProfile profile = profiles.findById(driverId).orElseThrow(DriverProfileNotFoundException::new);
        if (profile.getStatus() != DriverStatus.ONLINE) {
            throw new DriverOfflineException();
        }
        // SET NX with a TTL is an atomic "at most one per interval" gate, shared by every service instance
        Boolean allowed = redis.opsForValue().setIfAbsent("driver:" + driverId + ":ping", "1", props.minPingInterval());
        if (!Boolean.TRUE.equals(allowed)) {
            throw new TooManyUpdatesException();
        }

        index.upsert(profile.getVehicleType(), driverId, lat, lng);
        String message = toJson(driverId, lat, lng, heading, clock.millis());
        redis.opsForValue().set("driver:" + driverId + ":last", message, LAST_POSITION_TTL); // snapshot for late subscribers
        redis.convertAndSend(CHANNEL, message);
    }

    /** The wire format on the Redis channel and in the "last position" snapshot. Consumers parse this JSON. */
    static String toJson(UUID driverId, double lat, double lng, Double heading, long timestampMillis) {
        return String.format(Locale.ROOT, "{\"driverId\":\"%s\",\"lat\":%s,\"lng\":%s,\"heading\":%s,\"ts\":%d}",
                driverId, lat, lng, heading == null ? "null" : heading.toString(), timestampMillis);
    }
}
