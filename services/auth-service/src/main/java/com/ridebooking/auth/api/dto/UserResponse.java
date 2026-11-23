package com.ridebooking.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Outbound view of a user. Intentionally has no password hash or failed-login counter. */
public record UserResponse(UUID id, String email, String role, Instant createdAt) {}
