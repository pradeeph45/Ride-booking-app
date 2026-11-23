package com.ridebooking.auth.api.error;

import com.ridebooking.auth.domain.DuplicateEmailException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", "Request validation failed", fields));
    }

    /** Malformed JSON or an unknown enum value such as role=ADMIN. Details are not echoed back. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST", "Malformed request body"));
    }

    /** Domain invariants (e.g. builder rules) violated. Messages are authored by us, so they are safe. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiError> duplicate(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("EMAIL_ALREADY_REGISTERED", e.getMessage()));
    }

    /** Catch-all: log the detail server-side, return nothing internal to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
