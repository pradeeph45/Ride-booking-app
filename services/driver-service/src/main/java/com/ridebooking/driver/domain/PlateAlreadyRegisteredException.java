package com.ridebooking.driver.domain;

/** That vehicle belongs to another driver's profile. */
public class PlateAlreadyRegisteredException extends RuntimeException {

    public PlateAlreadyRegisteredException() {
        super("That plate number is already registered to another driver");
    }
}
