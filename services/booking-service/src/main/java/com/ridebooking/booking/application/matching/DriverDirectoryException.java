package com.ridebooking.booking.application.matching;

/** driver-service or auth-service could not be reached or refused us. Matching skips this round and tries again. */
public class DriverDirectoryException extends RuntimeException {

    public DriverDirectoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public DriverDirectoryException(String message) {
        super(message);
    }
}
