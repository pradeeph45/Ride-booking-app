package com.ridebooking.booking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.BookingStatusChangeRepository;
import com.ridebooking.booking.domain.OfferStatus;
import com.ridebooking.booking.domain.RideOffer;
import com.ridebooking.booking.domain.RideOfferRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Full stack against real MySQL and Redis. Authentication is simulated with a ready-made JWT principal (token issuing
 * belongs to auth-service). Driver-service is replaced by a fake directory, and the clock can be moved, so matching,
 * offers and their expiry are tested deterministically without any sleeping. The background matching schedule is
 * switched off: tests run a matching pass by hand with {@code MatchingService.tick()}.
 */
@SpringBootTest(properties = {
        "BOOKING_DB_PASSWORD=overridden-by-testcontainers",
        "BOOKING_CLIENT_SECRET=unused-in-tests",
        "ridebooking.matching.enabled=false"})
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.Infrastructure.class)
public abstract class BookingIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {
        @Bean
        @ServiceConnection
        MySQLContainer mysql() {
            return new MySQLContainer("mysql:8.4")
                    .withUrlParam("preserveInstants", "true")
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true");
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }

        @Bean
        @Primary
        FakeDriverDirectory fakeDriverDirectory() {
            return new FakeDriverDirectory();
        }
    }

    protected static final String MG_ROAD_TO_KORAMANGALA = """
            {"serviceType":"%s",
             "pickup":{"lat":12.9756,"lng":77.6050,"address":"MG Road"},
             "drop":{"lat":12.9352,"lng":77.6245,"address":"Koramangala"}}""";

    @Autowired protected MockMvc mvc;
    @Autowired protected BookingRepository bookings;
    @Autowired protected BookingStatusChangeRepository history;
    @Autowired protected RideOfferRepository offers;
    @Autowired protected com.ridebooking.booking.domain.RatingRepository ratings;
    @Autowired protected com.ridebooking.booking.domain.RatingStatsRepository ratingStats;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected MutableClock clock;
    @Autowired protected FakeDriverDirectory directory;

    /** Each test starts from an empty database, an empty Redis and no drivers on the map. */
    @BeforeEach
    void cleanState() {
        ratings.deleteAll();
        ratingStats.deleteAll();
        offers.deleteAll();
        history.deleteAll();
        bookings.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        directory.clear();
    }

    protected static RequestPostProcessor rider(UUID id) {
        return user(id, "RIDER");
    }

    protected static RequestPostProcessor driver(UUID id) {
        return user(id, "DRIVER");
    }

    private static RequestPostProcessor user(UUID id, String role) {
        return jwt()
                .jwt(j -> j.claim("uid", id.toString()).claim("role", role).claim("sid", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected ResultActions book(UUID riderId, String serviceType) throws Exception {
        return mvc.perform(post("/bookings").with(rider(riderId)).contentType(MediaType.APPLICATION_JSON)
                .content(MG_ROAD_TO_KORAMANGALA.formatted(serviceType)));
    }

    /** Books a ride and returns its id. */
    protected UUID bookRide(UUID riderId) throws Exception {
        String body = book(riderId, "CAR_MINI").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    protected ResultActions as(RequestPostProcessor who, String method, String path) throws Exception {
        return mvc.perform("GET".equals(method) ? get(path).with(who) : post(path).with(who));
    }

    /** Puts a live offer in front of a driver, as the matching pass would, without running it. */
    protected RideOffer offerTo(UUID bookingId, UUID driverId) {
        return offers.saveAndFlush(RideOffer.offer(bookingId, driverId, 1.0, clock.instant(), Duration.ofSeconds(60)));
    }

    /**
     * The driver accepts the ride. If matching has not offered it to them yet, the offer is created first: the same
     * state the dispatcher would have produced. Repeating the call is safe, like the real endpoint.
     */
    protected ResultActions accept(UUID driverId, UUID bookingId) throws Exception {
        RideOffer offer = offers.findByBookingId(bookingId).stream()
                .filter(o -> o.getDriverId().equals(driverId)).findFirst()
                .orElseGet(() -> offerTo(bookingId, driverId));
        return mvc.perform(post("/offers/" + offer.getId() + "/accept").with(driver(driverId)));
    }

    /** A trip that has been accepted, reached and started: the driver is now being metered. Returns the booking id. */
    protected UUID startedTrip(UUID riderId, UUID driverId) throws Exception {
        UUID ride = bookRide(riderId);
        accept(driverId, ride).andExpect(status().isOk());
        for (String step : java.util.List.of("arrive", "start")) {
            mvc.perform(post("/bookings/" + ride + "/" + step).with(driver(driverId))).andExpect(status().isOk());
        }
        return ride;
    }

    /** A GPS ping exactly as driver-service publishes it on the driver-locations channel. */
    protected static String pingJson(UUID driverId, double lat, double lng, long tsMillis) {
        return String.format(java.util.Locale.ROOT, "{\"driverId\":\"%s\",\"lat\":%s,\"lng\":%s,\"heading\":0.0,\"ts\":%d}",
                driverId, lat, lng, tsMillis);
    }

    protected long pendingOffers() {
        return offers.countByStatus(OfferStatus.PENDING);
    }
}
