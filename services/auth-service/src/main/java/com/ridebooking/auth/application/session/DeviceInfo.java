package com.ridebooking.auth.application.session;

/** What we know about the client that is logging in. */
public record DeviceInfo(String deviceName, String ipAddress, String userAgent) {

    public static DeviceInfo unknown() {
        return new DeviceInfo(null, null, null);
    }
}
