package com.ridebooking.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridebooking.tracking.application.LocationForwarder;
import com.ridebooking.tracking.application.TrackingAccess;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class LocationForwarderTest {

    final UUID driver = UUID.randomUUID();
    final UUID booking = UUID.randomUUID();
    TrackingAccess access;
    SimpMessagingTemplate messaging;
    LocationForwarder forwarder;

    @BeforeEach
    void setUp() {
        access = mock(TrackingAccess.class);
        messaging = mock(SimpMessagingTemplate.class);
        forwarder = new LocationForwarder(access, messaging);
    }

    private String ping(UUID driverId, String lat, String lng) {
        return "{\"driverId\":\"" + driverId + "\",\"lat\":" + lat + ",\"lng\":" + lng + ",\"heading\":90.5,\"ts\":1780000000000}";
    }

    @Test
    void sendsAPingToTheTopicOfTheDriversActiveTripWithoutTheDriverId() {
        when(access.activeBookingOf(driver)).thenReturn(Optional.of(booking));

        forwarder.forward(ping(driver, "12.9756", "77.605"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(messaging).convertAndSend(eq("/topic/bookings/" + booking + "/location"), payload.capture());
        assertThat(payload.getValue()).contains("\"lat\":12.9756").contains("\"lng\":77.605").contains("\"heading\":90.5")
                .contains("\"ts\":1780000000000")
                .doesNotContain(driver.toString()).doesNotContain("driverId");       // the rider learns where, not who
    }

    @Test
    void aDriverWithNoActiveTripIsIgnored() {
        when(access.activeBookingOf(driver)).thenReturn(Optional.empty());

        forwarder.forward(ping(driver, "12.9756", "77.605"));

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void garbageOnTheChannelCannotBreakTheForwarder() {
        for (String junk : new String[] {"not json", "{}", "{\"driverId\":\"nope\",\"lat\":1,\"lng\":2}", "", "[1,2,3]",
                ping(driver, "\"x\"", "77.6"), ping(driver, "95", "77.6"), ping(driver, "12.9", "190")}) {
            forwarder.forward(junk);                                                 // must not throw
        }

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void aPingWithoutAHeadingIsStillForwarded() {
        when(access.activeBookingOf(driver)).thenReturn(Optional.of(booking));

        forwarder.forward("{\"driverId\":\"" + driver + "\",\"lat\":12.9,\"lng\":77.6,\"heading\":null,\"ts\":1}");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(messaging).convertAndSend(anyString(), payload.capture());
        assertThat(payload.getValue()).contains("\"heading\":null");
    }
}
