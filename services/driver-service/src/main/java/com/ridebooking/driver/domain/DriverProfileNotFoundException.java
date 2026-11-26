package com.ridebooking.driver.domain;

/** The driver has not registered a vehicle yet. */
public class DriverProfileNotFoundException extends RuntimeException {

    public DriverProfileNotFoundException() {
        super("Register your vehicle first");
    }
}
