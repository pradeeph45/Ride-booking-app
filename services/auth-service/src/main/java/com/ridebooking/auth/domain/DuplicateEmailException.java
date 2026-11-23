package com.ridebooking.auth.domain;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email already registered");
    }
}
