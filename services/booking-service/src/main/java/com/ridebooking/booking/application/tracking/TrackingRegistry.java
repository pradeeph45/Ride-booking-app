package com.ridebooking.booking.application.tracking;

import com.ridebooking.booking.domain.Booking;
import com.ridebooking.booking.domain.BookingStatus;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Tells the tracking service which trips are live and who may watch them, through two small Redis entries:
 * <pre>
 *   tracking:booking:{bookingId} = {"riderId":..., "driverId":..., "status":...}   who may subscribe to this trip
 *   tracking:driver:{driverId}   = bookingId                                       where this driver's pings go
 * </pre>
 * Written only AFTER the database commit (so Redis never says something the database does not), and best-effort:
 * if Redis is down the trip itself is unaffected and only the live map pauses. Entries also expire on their own.
 */
@Component
public class TrackingRegistry {

    private static final Logger log = LoggerFactory.getLogger(TrackingRegistry.class);
    private static final Duration TTL = Duration.ofHours(6);
    private static final DefaultRedisScript<Long> DELETE_IF_EQUALS = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    public TrackingRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Brings Redis in line with the booking: live trips are registered, finished or cancelled ones removed. */
    public void publish(Booking booking) {
        try {
            if (isLive(booking)) {
                String json = String.format(Locale.ROOT, "{\"riderId\":\"%s\",\"driverId\":\"%s\",\"status\":\"%s\"}",
                        booking.getRiderId(), booking.getDriverId(), booking.getStatus());
                redis.opsForValue().set("tracking:booking:" + booking.getId(), json, TTL);
                redis.opsForValue().set("tracking:driver:" + booking.getDriverId(), booking.getId().toString(), TTL);
            } else {
                redis.delete("tracking:booking:" + booking.getId());
                if (booking.getDriverId() != null) {
                    // only if the driver is still pointed at THIS trip: they may already be on their next one
                    redis.execute(DELETE_IF_EQUALS, List.of("tracking:driver:" + booking.getDriverId()), booking.getId().toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not update live-tracking state for booking {}; the trip itself is unaffected", booking.getId(), e);
        }
    }

    private static boolean isLive(Booking b) {
        return b.getDriverId() != null && (b.getStatus() == BookingStatus.MATCHED
                || b.getStatus() == BookingStatus.ARRIVED || b.getStatus() == BookingStatus.STARTED);
    }
}
