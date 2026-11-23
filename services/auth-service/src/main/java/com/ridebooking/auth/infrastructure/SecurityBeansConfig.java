package com.ridebooking.auth.infrastructure;

import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.PasswordHasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class SecurityBeansConfig {

    /** One BCrypt encoder shared by user passwords, OTP hashes and client secrets. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    PasswordHasher passwordHasher(PasswordEncoder encoder) {
        return new BcryptPasswordHasher(encoder);
    }
}
