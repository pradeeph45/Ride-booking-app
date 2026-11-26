package com.ridebooking.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;

/**
 * The live-tracking channel end to end: a real WebSocket/STOMP client against the running service and a real Redis.
 * Tokens are fakes of the form "ROLE:uid" so the tests need no auth-service; everything else is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TrackingIT.Infrastructure.class)
class TrackingIT {

    static final String ORIGIN = "http://localhost:5173";

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        }

        /** "RIDER:{uid}" or "DRIVER:{uid}" is a valid token; anything else is rejected. */
        @Bean
        @Primary
        JwtDecoder fakeJwtDecoder() {
            return token -> {
                String[] parts = token.split(":");
                if (parts.length != 2) throw new BadJwtException("bad token");
                return Jwt.withTokenValue(token).header("alg", "none").subject(parts[1])
                        .claim("uid", parts[1]).claim("role", parts[0])
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
            };
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;

    final UUID rider = UUID.randomUUID();
    final UUID driver = UUID.randomUUID();
    final UUID booking = UUID.randomUUID();
    WebSocketStompClient client;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();   // the client needs one to track subscription receipts
        scheduler.setPoolSize(2);
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
    }

    @AfterEach
    void tearDown() {
        client.stop();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** What booking-service records when a driver is accepted for a ride. */
    private void activeTrip() {
        redis.opsForValue().set("tracking:booking:" + booking,
                "{\"riderId\":\"" + rider + "\",\"driverId\":\"" + driver + "\",\"status\":\"MATCHED\"}");
        redis.opsForValue().set("tracking:driver:" + driver, booking.toString());
    }

    /** What driver-service publishes on every GPS ping. */
    private void driverPings(double lat, double lng) {
        String json = "{\"driverId\":\"" + driver + "\",\"lat\":" + lat + ",\"lng\":" + lng + ",\"heading\":45.0,\"ts\":" + System.currentTimeMillis() + "}";
        redis.convertAndSend("driver-locations", json);
    }

    private static class Connection {
        StompSession session;                                   // null if the server refused the connection
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    }

    private Connection connect(String token) throws Exception {
        Connection c = new Connection();
        StompHeaders headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin(ORIGIN);
        CompletableFuture<StompSession> future = client.connectAsync("ws://localhost:" + port + "/ws", handshake, headers,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleFrame(StompHeaders h, Object payload) {
                        c.errors.add(h.getFirst("message") == null ? String.valueOf(payload) : h.getFirst("message"));
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable t) {
                        c.errors.add("transport: " + t.getMessage());
                    }
                });
        try {
            c.session = future.get(4, TimeUnit.SECONDS);
            } catch (Exception refused) {
            c.session = null;                                   // rejected or never connected
        }
        return c;
    }

    /** Subscribes and gives the broker a moment to register it (the simple broker sends no receipt frames). */
    private void subscribe(Connection c, UUID bookingId) throws Exception {
        c.session.subscribe("/topic/bookings/" + bookingId + "/location", new StompFrameHandler() {
            @Override public java.lang.reflect.Type getPayloadType(StompHeaders h) { return String.class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { c.messages.add((String) payload); }
        });
        Thread.sleep(500);
    }

    /** Sends the same ping repeatedly until a message containing {@code fragment} arrives (or 6 s pass); null if none. */
    private String pingUntilReceived(Connection c, double lat, double lng, String fragment) throws Exception {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            driverPings(lat, lng);
            String m = c.messages.poll(300, TimeUnit.MILLISECONDS);
            while (m != null) {
                if (m.contains(fragment)) return m;
                m = c.messages.poll(50, TimeUnit.MILLISECONDS);
            }
        }
        return null;
    }

    // ---- who may connect and watch -------------------------------------------------------------

    @Test
    void connectionsWithoutAValidTokenAreRefused() throws Exception {
        assertThat(connect(null).session).isNull();
        assertThat(connect("garbage").session).isNull();
        assertThat(connect("RIDER:not-a-uuid").session).isNull();
    }

    @Test
    void theRiderOfATripReceivesItsDriversPositionsLive() throws Exception {
        activeTrip();
        Connection c = connect("RIDER:" + rider);
        assertThat(c.session).isNotNull();
        subscribe(c, booking);

        String first = pingUntilReceived(c, 12.9760, 77.6055, "\"lat\":12.976");
        String second = pingUntilReceived(c, 12.9770, 77.6060, "\"lat\":12.977");   // and it keeps following the driver
        assertThat(first).contains("\"lng\":77.6055").contains("\"heading\":45.0");
        assertThat(second).contains("\"lng\":77.606");
        assertThat(first).doesNotContain(driver.toString());     // position only, never who is driving
    }

    @Test
    void theDriverOfATripMayWatchItToo() throws Exception {
        activeTrip();
        Connection c = connect("DRIVER:" + driver);

        subscribe(c, booking);

        assertThat(pingUntilReceived(c, 12.9760, 77.6055, "12.976")).isNotNull();
    }

    @Test
    void aStrangerCannotSubscribeToSomeoneElsesTrip() throws Exception {
        activeTrip();
        Connection stranger = connect("RIDER:" + UUID.randomUUID());
        assertThat(stranger.session).isNotNull();                 // anyone with a valid login may CONNECT...

        subscribe(stranger, booking);                             // ...but the SUBSCRIBE is refused
        driverPings(12.9760, 77.6055);

        assertThat(stranger.errors.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(stranger.messages.poll(1, TimeUnit.SECONDS)).isNull();   // and nothing is ever delivered
    }

    @Test
    void subscribingToAnUnknownTripIsRefused() throws Exception {
        Connection c = connect("RIDER:" + rider);

        subscribe(c, UUID.randomUUID());

        assertThat(c.errors.poll(3, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void clientsCannotSubscribeToAnythingExceptATripLocationTopic() throws Exception {
        activeTrip();
        Connection c = connect("RIDER:" + rider);

        c.session.subscribe("/topic/bookings/" + booking + "/anything-else", new StompSessionHandlerAdapter() {});

        assertThat(c.errors.poll(3, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void clientsCannotPublishMessagesAtAll() throws Exception {
        activeTrip();
        Connection watcher = connect("RIDER:" + rider);
        subscribe(watcher, booking);
        Connection attacker = connect("DRIVER:" + driver);

        attacker.session.send("/topic/bookings/" + booking + "/location", "{\"lat\":0,\"lng\":0}");   // forging a position

        assertThat(attacker.errors.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(watcher.messages.poll(1, TimeUnit.SECONDS)).isNull();      // the rider never sees a forged position
    }

    // ---- routing -------------------------------------------------------------------------------

    @Test
    void pingsFromADriverWithNoActiveTripGoNowhere() throws Exception {
        Connection c = connect("RIDER:" + rider);                 // no trip registered
        redis.opsForValue().set("tracking:booking:" + booking, "{\"riderId\":\"" + rider + "\",\"driverId\":\"" + driver + "\",\"status\":\"MATCHED\"}");
        subscribe(c, booking);                                    // allowed to watch, but the driver is not mapped to this trip

        driverPings(12.9760, 77.6055);

        assertThat(c.messages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void positionsAreOnlyDeliveredToTheTripTheDriverIsOn() throws Exception {
        activeTrip();
        UUID otherBooking = UUID.randomUUID();
        UUID otherRider = UUID.randomUUID();
        redis.opsForValue().set("tracking:booking:" + otherBooking, "{\"riderId\":\"" + otherRider + "\",\"driverId\":\"" + UUID.randomUUID() + "\",\"status\":\"STARTED\"}");
        Connection mine = connect("RIDER:" + rider);
        Connection theirs = connect("RIDER:" + otherRider);
        subscribe(mine, booking);
        subscribe(theirs, otherBooking);

        assertThat(pingUntilReceived(mine, 12.9760, 77.6055, "12.976")).isNotNull();
        assertThat(theirs.messages.poll(1, TimeUnit.SECONDS)).isNull();       // another trip's rider sees nothing
    }

    @Test
    void whenTheTripEndsThePositionsStop() throws Exception {
        activeTrip();
        Connection c = connect("RIDER:" + rider);
        subscribe(c, booking);
        assertThat(pingUntilReceived(c, 12.9760, 77.6055, "12.976")).isNotNull();

        redis.delete("tracking:driver:" + driver);                // what booking-service does when the trip completes
        Thread.sleep(300);
        c.messages.clear();                                        // drop pings that were already in flight
        driverPings(12.9770, 77.6060);

        assertThat(c.messages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    // ---- snapshot over HTTP (for the first paint and for reconnects) ---------------------------

    @Test
    void theRiderCanFetchTheLastKnownPositionWithoutWaitingForTheNextPing() throws Exception {
        activeTrip();
        redis.opsForValue().set("driver:" + driver + ":last",
                "{\"driverId\":\"" + driver + "\",\"lat\":12.976,\"lng\":77.6055,\"heading\":10.0,\"ts\":1780000000000}");

        mvc.perform(get("/tracking/bookings/" + booking).with(jwt().jwt(j -> j.claim("uid", rider.toString()).claim("role", "RIDER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_RIDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.location.lat").value(12.976))
                .andExpect(jsonPath("$.location.driverId").doesNotExist());
    }

    @Test
    void theSnapshotIsEmptyUntilTheDriverHasSentAPosition() throws Exception {
        activeTrip();

        mvc.perform(get("/tracking/bookings/" + booking).with(jwt().jwt(j -> j.claim("uid", rider.toString()).claim("role", "RIDER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_RIDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void strangersAndUnknownTripsGetTheSame404() throws Exception {
        activeTrip();

        mvc.perform(get("/tracking/bookings/" + booking).with(jwt().jwt(j -> j.claim("uid", UUID.randomUUID().toString()).claim("role", "RIDER"))
                .authorities(new SimpleGrantedAuthority("ROLE_RIDER")))).andExpect(status().isNotFound());
        mvc.perform(get("/tracking/bookings/" + UUID.randomUUID()).with(jwt().jwt(j -> j.claim("uid", rider.toString()).claim("role", "RIDER"))
                .authorities(new SimpleGrantedAuthority("ROLE_RIDER")))).andExpect(status().isNotFound());
        mvc.perform(get("/tracking/bookings/" + booking).accept("application/json")).andExpect(status().isUnauthorized());
    }
}
