package com.ridebooking.auth.api;

import com.ridebooking.auth.api.dto.SessionResponse;
import com.ridebooking.auth.application.session.SessionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session management. Every action is scoped to the caller's own user id taken from the token. */
@RestController
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/users/me/sessions")
    public ResponseEntity<List<SessionResponse>> mine(@AuthenticationPrincipal Jwt jwt) {
        UUID uid = uid(jwt);
        if (uid == null) return ResponseEntity.status(403).build();
        UUID current = sid(jwt);
        return ResponseEntity.ok(sessions.activeSessions(uid).stream()
                .map(s -> SessionResponse.from(s, current)).toList());
    }

    /** Ends one device. 404 for an unknown id and for someone else's id alike (no existence leak). */
    @DeleteMapping("/users/me/sessions/{id}")
    public ResponseEntity<Void> endOne(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID uid = uid(jwt);
        if (uid == null) return ResponseEntity.status(403).build();
        return sessions.revoke(uid, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** "Log out of all devices", including this one. */
    @DeleteMapping("/users/me/sessions")
    public ResponseEntity<Void> endAll(@AuthenticationPrincipal Jwt jwt) {
        UUID uid = uid(jwt);
        if (uid == null) return ResponseEntity.status(403).build();
        sessions.revokeAll(uid);
        return ResponseEntity.noContent().build();
    }

    /** Ends the session this token belongs to; the token stops working on the next request. */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        UUID uid = uid(jwt);
        UUID sid = sid(jwt);
        if (uid == null || sid == null) return ResponseEntity.status(403).build();
        sessions.revoke(uid, sid);
        return ResponseEntity.noContent().build();
    }

    private static UUID uid(Jwt jwt) {
        String v = jwt.getClaimAsString("uid");
        return v == null ? null : UUID.fromString(v);
    }

    private static UUID sid(Jwt jwt) {
        String v = jwt.getClaimAsString("sid");
        return v == null ? null : UUID.fromString(v);
    }
}
