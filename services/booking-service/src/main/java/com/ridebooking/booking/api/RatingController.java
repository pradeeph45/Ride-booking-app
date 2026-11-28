package com.ridebooking.booking.api;

import com.ridebooking.booking.api.dto.RatingRequest;
import com.ridebooking.booking.api.dto.RatingResponse;
import com.ridebooking.booking.api.dto.RatingSummaryResponse;
import com.ridebooking.booking.application.CurrentUser;
import com.ridebooking.booking.application.RatingService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Riders and drivers rate each other after a completed trip. Both roles use the same endpoints. */
@RestController
public class RatingController {

    private final RatingService service;

    public RatingController(RatingService service) {
        this.service = service;
    }

    /** First rating: 201. The same rating sent again (a retry): 200 with Idempotent-Replayed. A different one: 409. */
    @PostMapping("/bookings/{id}/rating")
    public ResponseEntity<RatingResponse> rate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @Valid @RequestBody RatingRequest request) {
        RatingService.Outcome outcome = service.rate(id, CurrentUser.from(jwt), request.score(), request.comment());
        RatingResponse body = RatingResponse.from(outcome.rating());
        return outcome.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body)
                : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** What I gave for this trip, or 204 if I have not rated it yet. */
    @GetMapping("/bookings/{id}/rating")
    public ResponseEntity<RatingResponse> mine(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.mine(id, CurrentUser.from(jwt))
                .map(r -> ResponseEntity.ok(RatingResponse.from(r)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** How others rate me, as an average and a count. */
    @GetMapping("/ratings/me")
    public RatingSummaryResponse summary(@AuthenticationPrincipal Jwt jwt) {
        return RatingSummaryResponse.from(service.summaryFor(CurrentUser.from(jwt)));
    }
}
