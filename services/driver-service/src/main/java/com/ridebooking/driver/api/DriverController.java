package com.ridebooking.driver.api;

import com.ridebooking.driver.api.dto.DriverProfileRequest;
import com.ridebooking.driver.api.dto.DriverProfileResponse;
import com.ridebooking.driver.api.dto.LocationRequest;
import com.ridebooking.driver.application.DriverLocationService;
import com.ridebooking.driver.application.DriverProfileService;
import com.ridebooking.driver.application.UpsertResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything here is about the CALLER's own driver profile ("/me"). The driver id is taken from the verified
 * token only, so there is no id in any URL to tamper with. The DRIVER role is enforced in SecurityConfig.
 */
@RestController
@RequestMapping("/drivers/me")
public class DriverController {

    private final DriverProfileService profiles;
    private final DriverLocationService locations;

    public DriverController(DriverProfileService profiles, DriverLocationService locations) {
        this.profiles = profiles;
        this.locations = locations;
    }

    private static UUID me(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("uid"));
    }

    /** Registers the vehicle (201) or updates it (200). */
    @PutMapping
    public ResponseEntity<DriverProfileResponse> upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DriverProfileRequest request) {
        UpsertResult result = profiles.upsert(me(jwt), request.vehicleType(), request.plateNumber(), request.vehicleModel());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(DriverProfileResponse.from(result.profile()));
    }

    @GetMapping
    public DriverProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
        return DriverProfileResponse.from(profiles.get(me(jwt)));
    }

    @PostMapping("/online")
    public DriverProfileResponse online(@AuthenticationPrincipal Jwt jwt) {
        return DriverProfileResponse.from(profiles.goOnline(me(jwt)));
    }

    @PostMapping("/offline")
    public DriverProfileResponse offline(@AuthenticationPrincipal Jwt jwt) {
        return DriverProfileResponse.from(profiles.goOffline(me(jwt)));
    }

    /** A GPS ping. Send every few seconds while online. */
    @PostMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocationRequest request) {
        locations.update(me(jwt), request.lat(), request.lng(), request.heading());
    }
}
