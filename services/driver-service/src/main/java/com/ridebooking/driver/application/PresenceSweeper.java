package com.ridebooking.driver.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically drops drivers who stopped sending heartbeats (app killed, phone out of signal). */
@Component
@ConditionalOnProperty(name = "ridebooking.presence.sweeper", havingValue = "true", matchIfMissing = true)
class PresenceSweeper {

    private static final Logger log = LoggerFactory.getLogger(PresenceSweeper.class);

    private final DriverGeoIndex index;

    PresenceSweeper(DriverGeoIndex index) {
        this.index = index;
    }

    @Scheduled(fixedDelayString = "${ridebooking.presence.sweep-interval:10s}", initialDelayString = "${ridebooking.presence.sweep-interval:10s}")
    void sweep() {
        try {
            int removed = index.sweepStale();
            if (removed > 0) {
                log.info("Swept {} silent driver(s) from the live index", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Presence sweep failed; it will be retried", e); // Redis blip: the next tick tries again
        }
    }
}
