package com.ridebooking.tracking.api;

import com.ridebooking.tracking.application.LocationSnapshot;
import com.ridebooking.tracking.application.TrackingAccess;
import com.ridebooking.tracking.application.TrackingInfo;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A one-off look at a live trip, for the first paint of the map and for catching up after a dropped connection. */
@RestController
@RequestMapping("/tracking")
public class TrackingController {

    /** {@code location} is null until the driver has sent a position. It never says who the driver is. */
    public record TrackingView(String status, LocationSnapshot location) {}

    private final TrackingAccess access;

    public TrackingController(TrackingAccess access) {
        this.access = access;
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<TrackingView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        String uid = jwt.getClaimAsString("uid");
        if (uid == null) {
            return ResponseEntity.status(403).build(); // a service token has no user behind it
        }
        UUID userId = UUID.fromString(uid);
        // "not yours" and "no such live trip" are the same 404: trip ids cannot be probed
        return access.find(id).filter(trip -> trip.mayView(userId))
                .map((TrackingInfo trip) -> ResponseEntity.ok(new TrackingView(trip.status(),
                        access.lastLocation(trip.driverId()).orElse(null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
