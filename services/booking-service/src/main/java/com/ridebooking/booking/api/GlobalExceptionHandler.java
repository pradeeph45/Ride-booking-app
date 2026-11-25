package com.ridebooking.booking.api;

import com.ridebooking.booking.application.IdempotencyKeyReusedException;
import com.ridebooking.booking.domain.BookingAccessDeniedException;
import com.ridebooking.booking.domain.BookingNotFoundException;
import com.ridebooking.booking.domain.InvalidBookingTransitionException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", "Request validation failed", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed() {
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST", "Malformed request"));
    }

    /** Messages come from our own code (bad coordinates, trip too short, bad cursor), so they are safe to show. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    ResponseEntity<ApiError> notFound(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("BOOKING_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(BookingAccessDeniedException.class)
    ResponseEntity<ApiError> denied(BookingAccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("ACCESS_DENIED", e.getMessage()));
    }

    /** The action is not legal in the booking's current state (already accepted, already cancelled...). */
    @ExceptionHandler(InvalidBookingTransitionException.class)
    ResponseEntity<ApiError> badState(InvalidBookingTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("INVALID_STATE", e.getMessage()));
    }

    /**
     * Someone else changed the same booking at the same instant (e.g. two drivers accepting): version
     * conflict, lock timeout or a database deadlock victim. All are "try again" situations, never a 500.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("BOOKING_CONFLICT", "The booking was just changed by someone else. Please refresh."));
    }

    /** Same Idempotency-Key, different request: refuse rather than guess which one the client meant. */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ApiError> keyReused(IdempotencyKeyReusedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiError.of("IDEMPOTENCY_KEY_REUSED", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        if (e instanceof ErrorResponse spring) { // framework errors keep their own status: 404 unknown URL, 405 wrong method...
            return ResponseEntity.status(spring.getStatusCode()).body(ApiError.of("HTTP_" + spring.getStatusCode().value(), "Request failed"));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
