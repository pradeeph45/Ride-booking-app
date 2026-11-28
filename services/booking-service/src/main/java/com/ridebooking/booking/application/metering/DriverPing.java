package com.ridebooking.booking.application.metering;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.json.JsonParserFactory;

/** One GPS ping as published by driver-service on the driver-locations channel. */
public record DriverPing(UUID driverId, double lat, double lng, long ts) {

    /** @return empty for anything that is not a well-formed ping, so a bad message can never touch a trip. */
    public static Optional<DriverPing> parse(String json) {
        try {
            var m = JsonParserFactory.getJsonParser().parseMap(json);
            if (!(m.get("lat") instanceof Number lat) || !(m.get("lng") instanceof Number lng)
                    || !(m.get("ts") instanceof Number ts) || !(m.get("driverId") instanceof String id)) {
                return Optional.empty();
            }
            return Optional.of(new DriverPing(UUID.fromString(id), lat.doubleValue(), lng.doubleValue(), ts.longValue()));
        } catch (RuntimeException notAPing) {
            return Optional.empty();
        }
    }
}
