package com.ridebooking.gateway.bff;

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
public class BffExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BffExceptionHandler.class);

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_CREDENTIALS"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED"));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<Map<String, String>> upstream(UpstreamUnavailableException e) {
        log.error("Auth service unavailable", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "UPSTREAM_UNAVAILABLE"));
    }
}
