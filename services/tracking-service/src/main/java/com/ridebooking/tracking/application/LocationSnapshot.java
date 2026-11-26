package com.ridebooking.tracking.application;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.json.JsonParserFactory;

/**
 * A driver's position as shown to a rider: where and which way, never WHO. Built only from well-formed input, so
 * a bad message on the Redis channel can never reach a browser.
 */
public record LocationSnapshot(double lat, double lng, Double heading, long ts) {

    /** @return empty for anything that is not a valid position */
    public static Optional<LocationSnapshot> parse(String json) {
        try {
            return fromMap(JsonParserFactory.getJsonParser().parseMap(json));
        } catch (RuntimeException notJsonObject) {
            return Optional.empty();
        }
    }

    public static Optional<LocationSnapshot> fromMap(Map<String, Object> m) {
        if (!(m.get("lat") instanceof Number lat) || !(m.get("lng") instanceof Number lng)) {
            return Optional.empty();
        }
        double la = lat.doubleValue(), lo = lng.doubleValue();
        if (!Double.isFinite(la) || la < -90 || la > 90 || !Double.isFinite(lo) || lo < -180 || lo > 180) {
            return Optional.empty();
        }
        Double heading = m.get("heading") instanceof Number h && Double.isFinite(h.doubleValue()) ? h.doubleValue() : null;
        long ts = m.get("ts") instanceof Number t ? t.longValue() : 0L;
        return Optional.of(new LocationSnapshot(la, lo, heading, ts));
    }

    /** The message pushed to browsers. Written by hand so the shape is exactly this and nothing else. */
    public String toJson() {
        return String.format(Locale.ROOT, "{\"lat\":%s,\"lng\":%s,\"heading\":%s,\"ts\":%d}", lat, lng,
                heading == null ? "null" : heading.toString(), ts);
    }
}
