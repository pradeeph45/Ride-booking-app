package com.ridebooking.driver.api;

import com.ridebooking.driver.api.dto.NearbyDriverResponse;
import com.ridebooking.driver.application.DriverGeoIndex;
import com.ridebooking.driver.domain.VehicleType;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * For other services only. Not reachable by users: it requires the "drivers.read" scope, which only a
 * client-credentials token for a registered service carries (see SecurityConfig).
 */
@RestController
@RequestMapping("/internal/drivers")
public class InternalDriverController {

    static final double MAX_RADIUS_KM = 25;
    static final int MAX_LIMIT = 50;

    private final DriverGeoIndex index;

    public InternalDriverController(DriverGeoIndex index) {
        this.index = index;
    }

    @GetMapping("/nearby")
    public List<NearbyDriverResponse> nearby(@RequestParam double lat, @RequestParam double lng, @RequestParam VehicleType type,
                                             @RequestParam(defaultValue = "5") double radiusKm,
                                             @RequestParam(defaultValue = "10") int limit) {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lng) || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Coordinates are outside the valid range");
        }
        double radius = Math.max(0.1, Math.min(radiusKm, MAX_RADIUS_KM)); // a caller cannot ask for the whole planet
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        return index.nearby(type, lat, lng, radius, size).stream().map(NearbyDriverResponse::from).toList();
    }
}
