package com.ridebooking.tracking.infrastructure;

import com.ridebooking.tracking.application.LocationForwarder;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor auth;
    private final TrackingProperties props;

    WebSocketConfig(StompAuthInterceptor auth, TrackingProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Plain WebSocket; only the configured browser origin may open one.
        registry.addEndpoint("/ws").setAllowedOrigins(props.allowedOrigin());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeats = new ThreadPoolTaskScheduler();
        heartbeats.setPoolSize(1);
        heartbeats.setThreadNamePrefix("ws-heartbeat-");
        heartbeats.initialize();
        // Server -> browser topics only. There is no application destination prefix, so clients have nowhere to SEND.
        registry.enableSimpleBroker("/topic").setHeartbeatValue(new long[] {10_000, 10_000}).setTaskScheduler(heartbeats);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(8 * 1024).setSendTimeLimit(10_000).setSendBufferSizeLimit(256 * 1024);
    }

    /** Listens to every driver GPS ping on the shared Redis channel and hands it to the forwarder. */
    @Bean
    RedisMessageListenerContainer driverLocationListener(RedisConnectionFactory connections, LocationForwarder forwarder) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener((message, pattern) -> forwarder.forward(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic("driver-locations"));
        return container;
    }
}
