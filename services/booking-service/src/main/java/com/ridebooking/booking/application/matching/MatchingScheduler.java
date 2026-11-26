package com.ridebooking.booking.application.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs a matching pass every couple of seconds. Off in tests, where the pass is triggered by hand. */
@Component
@ConditionalOnProperty(name = "ridebooking.matching.enabled", havingValue = "true", matchIfMissing = true)
class MatchingScheduler {

    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);

    private final MatchingService matching;

    MatchingScheduler(MatchingService matching) {
        this.matching = matching;
    }

    @Scheduled(fixedDelayString = "${ridebooking.matching.tick:2s}", initialDelayString = "${ridebooking.matching.tick:2s}")
    void run() {
        try {
            matching.tick();
        } catch (RuntimeException e) {
            log.warn("Matching pass failed; it will run again", e); // one bad pass must never stop the schedule
        }
    }
}
