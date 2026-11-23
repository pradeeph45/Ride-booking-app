package com.ridebooking.auth.api;

import com.ridebooking.auth.api.dto.UserResponse;
import com.ridebooking.auth.api.mapper.UserMapper;
import com.ridebooking.auth.domain.UserRepository;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class ProfileController {

    private final UserRepository users;
    private final UserMapper mapper;

    public ProfileController(UserRepository users, UserMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    /** Identity comes only from the verified token, never from a client-supplied id (no IDOR). */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        String uid = jwt.getClaimAsString("uid");
        if (uid == null) {
            return ResponseEntity.status(403).build(); // e.g. a client-credentials token has no user
        }
        return users.findById(UUID.fromString(uid))
                .map(mapper::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
