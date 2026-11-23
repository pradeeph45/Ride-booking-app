package com.ridebooking.auth.domain;

import org.springframework.security.crypto.password.PasswordEncoder;

/** Adapter: BCrypt via Spring's PasswordEncoder. Swappable without touching User. */
public class BcryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;

    public BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        return hash != null && encoder.matches(rawPassword, hash);
    }
}
