package com.ridebooking.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.PasswordHasher;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Runs Flyway migrations against a real MySQL 8.4 so schema and mapping drift is caught. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryIT {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired UserRepository users;

    private final PasswordHasher hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder(4));

    private User user(String email, Role role) {
        return User.builder().email(email).password("S3cret-pass!", hasher).role(role).build();
    }

    @Test
    void savesAndFindsByEmail() {
        users.saveAndFlush(user("find@me.com", Role.RIDER));

        assertThat(users.findByEmail("find@me.com")).isPresent()
                .get().extracting(User::getRole).isEqualTo(Role.RIDER);
        assertThat(users.findByEmail("nobody@me.com")).isEmpty();
    }

    @Test
    void emailMustBeUnique() {
        users.saveAndFlush(user("dup@me.com", Role.RIDER));

        assertThatThrownBy(() ->
                users.saveAndFlush(user("dup@me.com", Role.DRIVER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
