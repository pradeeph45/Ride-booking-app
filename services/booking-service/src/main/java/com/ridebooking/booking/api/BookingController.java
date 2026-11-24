package com.ridebooking.booking.api;

import com.ridebooking.booking.api.dto.BookingPageResponse;
import com.ridebooking.booking.api.dto.BookingResponse;
import com.ridebooking.booking.api.dto.CreateBookingRequest;
import com.ridebooking.booking.api.dto.FareEstimateRequest;
import com.ridebooking.booking.api.dto.FareQuoteResponse;
import com.ridebooking.booking.application.BookingPage;
import com.ridebooking.booking.application.BookingService;
import com.ridebooking.booking.application.CurrentUser;
import com.ridebooking.booking.domain.BookingStatus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role rules (RIDER vs DRIVER) are enforced centrally in SecurityConfig; ownership rules (is this MY
 * booking?) are enforced in BookingService, using the identity from the verified token only.
 */
@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    // ---- rider ---------------------------------------------------------------------------------

    @PostMapping("/estimate")
    public List<FareQuoteResponse> estimate(@Valid @RequestBody FareEstimateRequest request) {
        return service.estimate(request.pickup().toDomain(), request.drop().toDomain())
                .stream().map(FareQuoteResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateBookingRequest request) {
        return BookingResponse.from(service.create(CurrentUser.from(jwt), request.serviceType(),
                request.pickup().toDomain(), request.drop().toDomain()));
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.cancel(id, CurrentUser.from(jwt)));
    }

    // ---- both ----------------------------------------------------------------------------------

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.get(id, CurrentUser.from(jwt)));
    }

    /** Ride history: the caller's own bookings, newest first. Page with ?cursor= (from the previous page). */
    @GetMapping
    public BookingPageResponse history(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) BookingStatus status,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") int limit) {
        BookingPage page = service.history(CurrentUser.from(jwt), status, cursor, limit);
        return new BookingPageResponse(page.items().stream().map(BookingResponse::from).toList(), page.nextCursor());
    }

    // ---- driver --------------------------------------------------------------------------------

    @GetMapping("/open")
    public List<BookingResponse> open(@AuthenticationPrincipal Jwt jwt) {
        return service.openRequests(CurrentUser.from(jwt)).stream().map(BookingResponse::from).toList();
    }

    @PostMapping("/{id}/accept")
    public BookingResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.accept(id, CurrentUser.from(jwt)));
    }

    @PostMapping("/{id}/arrive")
    public BookingResponse arrive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.arrive(id, CurrentUser.from(jwt)));
    }

    @PostMapping("/{id}/start")
    public BookingResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.start(id, CurrentUser.from(jwt)));
    }

    @PostMapping("/{id}/complete")
    public BookingResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(service.complete(id, CurrentUser.from(jwt)));
    }
}
