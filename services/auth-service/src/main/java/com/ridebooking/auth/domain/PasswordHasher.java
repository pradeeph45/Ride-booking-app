package com.ridebooking.auth.domain;

/** Port: the domain decides it needs hashing, not which algorithm (Dependency Inversion). */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
