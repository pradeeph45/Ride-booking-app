package com.ridebooking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.DuplicateEmailException;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository users;

    private User newUser() {
        return User.builder().email("a@b.com")
                .password("S3cret-pass!", new BcryptPasswordHasher(new BCryptPasswordEncoder(4)))
                .role(Role.RIDER).build();
    }

    @Test
    void registersNewUser() {
        User user = newUser();
        when(users.existsByEmail("a@b.com")).thenReturn(false);
        when(users.saveAndFlush(user)).thenReturn(user);

        assertThat(new UserService(users).register(user)).isSameAs(user);
    }

    @Test
    void rejectsDuplicateEmailWithoutSaving() {
        User user = newUser();
        when(users.existsByEmail("a@b.com")).thenReturn(true);

        assertThatThrownBy(() -> new UserService(users).register(user))
                .isInstanceOf(DuplicateEmailException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void translatesRaceOnUniqueConstraintToDuplicateEmail() {
        User user = newUser();
        when(users.existsByEmail("a@b.com")).thenReturn(false);
        when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> new UserService(users).register(user))
                .isInstanceOf(DuplicateEmailException.class);
    }
}
