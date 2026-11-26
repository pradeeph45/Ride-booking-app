package com.ridebooking.driver.domain;

/** The driver's own intent: ONLINE means "send me rides". Actual liveness is tracked by heartbeats in Redis. */
public enum DriverStatus {
    OFFLINE,
    ONLINE
}
