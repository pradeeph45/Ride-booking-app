package com.ridebooking.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move forward, so offer expiry can be tested without sleeping. Like a real clock it also ticks
 * forward 1 ms on every reading, so two rows created one after the other never share a timestamp (which would make
 * "newest first" and audit-trail ordering ambiguous).
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override
    public synchronized Instant instant() {
        Instant reading = now;
        now = now.plusMillis(1);
        return reading;
    }
}
