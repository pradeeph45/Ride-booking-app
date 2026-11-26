package com.ridebooking.driver.api;

import com.ridebooking.driver.domain.DriverOfflineException;
import com.ridebooking.driver.domain.DriverProfileNotFoundException;
import com.ridebooking.driver.domain.PlateAlreadyRegisteredException;
import com.ridebooking.driver.domain.TooManyUpdatesException;
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

    /** Messages come from our own validation code, so they are safe to show. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(DriverProfileNotFoundException.class)
    ResponseEntity<ApiError> noProfile(DriverProfileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of("DRIVER_PROFILE_REQUIRED", e.getMessage()));
    }

    @ExceptionHandler(DriverOfflineException.class)
    ResponseEntity<ApiError> offline(DriverOfflineException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("DRIVER_OFFLINE", e.getMessage()));
    }

    @ExceptionHandler(PlateAlreadyRegisteredException.class)
    ResponseEntity<ApiError> plate(PlateAlreadyRegisteredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("PLATE_ALREADY_REGISTERED", e.getMessage()));
    }

    @ExceptionHandler(TooManyUpdatesException.class)
    ResponseEntity<ApiError> tooMany(TooManyUpdatesException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1")
                .body(ApiError.of("TOO_MANY_UPDATES", e.getMessage()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("CONFLICT", "Please try again"));
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
