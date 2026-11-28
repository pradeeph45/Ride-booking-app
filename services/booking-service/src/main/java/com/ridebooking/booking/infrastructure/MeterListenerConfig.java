package com.ridebooking.booking.infrastructure;

import com.ridebooking.booking.application.metering.TripMeter;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Feeds every driver GPS ping to the trip meter. Pings are handled one at a time, in arrival order, by a single
 * worker with a bounded queue: the meter needs them in order, and a burst must never spawn unlimited threads.
 */
@Configuration
@ConditionalOnProperty(name = "ridebooking.metering.listener", havingValue = "true", matchIfMissing = true)
class MeterListenerConfig {

    private static final Logger log = LoggerFactory.getLogger(MeterListenerConfig.class);

    @Bean
    RedisMessageListenerContainer meterListener(RedisConnectionFactory connections, TripMeter meter) {
        ThreadPoolTaskExecutor worker = new ThreadPoolTaskExecutor();
        worker.setCorePoolSize(1);
        worker.setMaxPoolSize(1);
        worker.setQueueCapacity(10_000);
        worker.setThreadNamePrefix("trip-meter-");
        worker.initialize();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.setTaskExecutor(worker);                                             // handles messages, in order
        container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor("meter-sub-"));  // holds the subscription open
        container.addMessageListener((message, pattern) -> {
            try {
                meter.record(new String(message.getBody(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                log.warn("Could not meter a driver ping; it is skipped", e); // one bad ping never stops the stream
            }
        }, new ChannelTopic("driver-locations"));
        return container;
    }
}
