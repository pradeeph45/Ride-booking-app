package com.ridebooking.tracking.infrastructure;

import com.ridebooking.tracking.application.TrackingAccess;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * The gatekeeper for every WebSocket frame a browser sends. A browser WebSocket cannot set an Authorization header, so
 * the token travels in the STOMP CONNECT frame (never in the URL, where it would be logged). Rules:
 * <ul>
 *   <li>CONNECT needs a valid, un-revoked access token; the user becomes the connection's principal;</li>
 *   <li>SUBSCRIBE is allowed ONLY to /topic/bookings/{id}/location, and only for the rider or driver of that live trip;</li>
 *   <li>every other command, notably SEND, is refused: browsers can listen, never speak. Positions come only from
 *       driver-service, so nobody can forge one.</li>
 * </ul>
 */
@Component
class StompAuthInterceptor implements ChannelInterceptor {

    private static final Pattern TRIP_TOPIC = Pattern.compile(
            "^/topic/bookings/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/location$");

    private final JwtDecoder decoder;
    private final TrackingAccess access;

    StompAuthInterceptor(JwtDecoder decoder, TrackingAccess access) {
        this.decoder = decoder;
        this.access = access;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.isHeartbeat() || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> authenticate(accessor, message);
            case SUBSCRIBE -> authorizeSubscription(accessor, message);
            case UNSUBSCRIBE, DISCONNECT -> { /* always allowed */ }
            default -> throw new MessageDeliveryException(message, "Command not allowed");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException(message, "Unauthorized");
        }
        try {
            Jwt jwt = decoder.decode(header.substring(7));
            String uid = jwt.getClaimAsString("uid");
            UUID.fromString(uid); // must be a user token carrying a real user id (a service token has none)
            accessor.setUser(new UsernamePasswordAuthenticationToken(uid, null, List.of()));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new MessageDeliveryException(message, "Unauthorized");
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor, Message<?> message) {
        Principal user = accessor.getUser();
        String destination = accessor.getDestination();
        Matcher topic = destination == null ? null : TRIP_TOPIC.matcher(destination);
        if (user == null || topic == null || !topic.matches()) {
            throw new MessageDeliveryException(message, "Forbidden");
        }
        UUID userId = UUID.fromString(user.getName());
        boolean allowed = access.find(UUID.fromString(topic.group(1))).map(trip -> trip.mayView(userId)).orElse(false);
        if (!allowed) {
            throw new MessageDeliveryException(message, "Forbidden"); // same answer for "not yours" and "no such trip"
        }
    }
}
