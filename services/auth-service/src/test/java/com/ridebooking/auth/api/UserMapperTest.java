package com.ridebooking.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.auth.api.dto.SignupRole;
import com.ridebooking.auth.api.dto.UserRequest;
import com.ridebooking.auth.api.dto.UserResponse;
import com.ridebooking.auth.api.mapper.UserMapper;
import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserMapperTest {

    private final UserMapper mapper =
            new UserMapper(new BcryptPasswordHasher(new BCryptPasswordEncoder(4)));

    @Test
    void mapsRequestToUserWithHashedPassword() {
        User user = mapper.toUser(new UserRequest(" Driver@Example.com", "S3cret-pass!", SignupRole.DRIVER));

        assertThat(user.getEmail()).isEqualTo("driver@example.com");
        assertThat(user.getRole()).isEqualTo(Role.DRIVER);
        assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo("S3cret-pass!");
    }

    @Test
    void responseExposesNoSecrets() {
        User user = mapper.toUser(new UserRequest("a@b.com", "S3cret-pass!", SignupRole.RIDER));

        UserResponse response = mapper.toResponse(user);

        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.role()).isEqualTo("RIDER");
        assertThat(response.toString()).doesNotContain(user.getPasswordHash());
        assertThat(UserResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("password", "passwordHash");
    }
}
