package com.ridebooking.auth.api.dto;

/** Roles a person may pick when signing up. ADMIN is deliberately absent (privilege escalation). */
public enum SignupRole {
    RIDER,
    DRIVER
}
