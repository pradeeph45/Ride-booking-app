package com.ridebooking.gateway.bff;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;

/**
 * Backend-for-frontend for the React app. Holds the OAuth2 client secret, trades email+OTP for
 * tokens, and keeps the long-lived refresh token in an HttpOnly cookie that JavaScript cannot read
 * (so an XSS bug cannot steal it). Only the 15-minute access token is handed to the page.
 *
 * <p>CSRF: the cookie is SameSite=Strict, scoped to /bff, and every call must carry a custom header
 * (X-RB-CSRF). A cross-site form or image cannot add custom headers, and a cross-origin fetch with one
 * triggers a CORS preflight that only the configured origin passes.
 */
@RestController
@RequestMapping("/bff")
public class BffController {

    static final String CSRF_HEADER = "X-RB-CSRF";
    private static final Duration REFRESH_COOKIE_TTL = Duration.ofDays(30);

    private final TokenClient tokens;
    private final BffProperties props;

    public BffController(TokenClient tokens, BffProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(HttpServletRequest request, @Valid @RequestBody LoginRequest body) {
        requireCsrfHeader(request);
        return withRefreshCookie(tokens.otpLogin(body.email(), body.otp(), request.getHeader(HttpHeaders.USER_AGENT)));
    }

    /** "Sign in with Google": the page sends the ID token from Google's button; we verify it server-side. */
    @PostMapping("/google")
    public ResponseEntity<SessionResponse> google(HttpServletRequest request, @Valid @RequestBody GoogleLoginRequest body) {
        requireCsrfHeader(request);
        return withRefreshCookie(tokens.googleLogin(body.idToken(), body.role(), request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        requireCsrfHeader(request);
        Cookie cookie = WebUtils.getCookie(request, props.refreshCookieName());
        if (cookie == null || cookie.getValue().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return withRefreshCookie(tokens.refresh(cookie.getValue()));
        } catch (InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                    .build(); // a dead refresh token is dropped so the client stops retrying with it
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        requireCsrfHeader(request);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                tokens.logout(authorization.substring(7));
            } catch (RuntimeException ignored) {
                // token already dead or auth-service down: still clear the local cookie
            }
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<SessionResponse> withRefreshCookie(TokenResponse issued) {
        if (issued.refreshToken() == null) {
            throw new InvalidCredentialsException();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(issued.refreshToken(), REFRESH_COOKIE_TTL).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(SessionResponse.from(issued));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.refreshCookieName(), value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/bff")
                .maxAge(maxAge)
                .build();
    }

    private static void requireCsrfHeader(HttpServletRequest request) {
        if (request.getHeader(CSRF_HEADER) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + CSRF_HEADER);
        }
    }
}
