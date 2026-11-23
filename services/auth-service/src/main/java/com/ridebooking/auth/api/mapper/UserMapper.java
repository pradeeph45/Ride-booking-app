package com.ridebooking.auth.api.mapper;

import com.ridebooking.auth.api.dto.UserRequest;
import com.ridebooking.auth.api.dto.UserResponse;
import com.ridebooking.auth.domain.PasswordHasher;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import org.springframework.stereotype.Component;

/** Translates between the API contract and the domain so neither leaks into the other. */
@Component
public class UserMapper {

    private final PasswordHasher hasher;

    public UserMapper(PasswordHasher hasher) {
        this.hasher = hasher;
    }

    public User toUser(UserRequest request) {
        return User.builder()
                .email(request.email())
                .password(request.password(), hasher)
                .role(Role.valueOf(request.role().name()))
                .build();
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
    }
}
