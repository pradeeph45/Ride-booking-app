package com.ridebooking.booking.api;

import com.ridebooking.booking.api.dto.BookingResponse;
import com.ridebooking.booking.api.dto.OfferResponse;
import com.ridebooking.booking.application.CurrentUser;
import com.ridebooking.booking.application.matching.OfferService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ride offers made to the calling driver. Role DRIVER is enforced in SecurityConfig; ownership in OfferService. */
@RestController
@RequestMapping("/offers")
public class OfferController {

    private final OfferService offers;

    public OfferController(OfferService offers) {
        this.offers = offers;
    }

    /** Offers waiting for this driver's answer. Poll this while online. */
    @GetMapping("/mine")
    public List<OfferResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return offers.liveOffers(CurrentUser.from(jwt)).stream().map(OfferResponse::from).toList();
    }

    /** Taking the ride. Repeating it is safe. 409 if it expired or the rider cancelled. */
    @PostMapping("/{id}/accept")
    public BookingResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BookingResponse.from(offers.accept(id, CurrentUser.from(jwt)));
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        offers.reject(id, CurrentUser.from(jwt));
    }
}
