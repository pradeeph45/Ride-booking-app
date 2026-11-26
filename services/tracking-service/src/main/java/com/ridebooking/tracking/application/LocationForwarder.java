package com.ridebooking.tracking.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The bridge from "a driver sent a GPS ping" (Redis channel) to "the rider's screen moves" (WebSocket topic).
 * Each ping is delivered only to the topic of the trip that driver is on, and only the position is forwarded.
 * Anything malformed is dropped silently: one bad message must never disturb the stream for everybody else.
 */
@Component
public class LocationForwarder {

    private static final Logger log = LoggerFactory.getLogger(LocationForwarder.class);

    private final TrackingAccess access;
    private final SimpMessagingTemplate messaging;

    public LocationForwarder(TrackingAccess access, SimpMessagingTemplate messaging) {
        this.access = access;
        this.messaging = messaging;
    }

    public void forward(String json) {
        try {
            Map<String, Object> ping = JsonParserFactory.getJsonParser().parseMap(json);
            UUID driverId = UUID.fromString(String.valueOf(ping.get("driverId")));
            Optional<LocationSnapshot> position = LocationSnapshot.fromMap(ping);
            if (position.isEmpty()) {
                return;
            }
            access.activeBookingOf(driverId).ifPresent(bookingId ->
                    messaging.convertAndSend("/topic/bookings/" + bookingId + "/location", position.get().toJson()));
        } catch (RuntimeException ignored) {
            log.debug("Ignored an unreadable location message");
        }
    }
}
