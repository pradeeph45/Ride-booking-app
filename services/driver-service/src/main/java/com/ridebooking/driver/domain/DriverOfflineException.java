package com.ridebooking.driver.domain;

/** Location updates are only accepted while the driver is ONLINE. */
public class DriverOfflineException extends RuntimeException {

    public DriverOfflineException() {
        super("Go online before sharing your location");
    }
}
