package com.ridebooking.booking.application.metering;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ridebooking.metering")
public record MeteringProperties(
        /* false in tests that publish pings by hand */
        @DefaultValue("true") boolean listener,
        /* a hop implying more than this speed is a GPS glitch, not driving (the demo simulator does about 156) */
        @DefaultValue("200") double maxSpeedKmh,
        /* movement smaller than this is GPS wobble while standing still and is not counted */
        @DefaultValue("5") double minMoveMeters,
        /* after this many glitches in a row the new position is trusted: the old anchor was probably the bad one */
        @DefaultValue("3") int maxConsecutiveJumps,
        /* meter state outlives any trip */
        @DefaultValue("6h") Duration stateTtl) {
}
