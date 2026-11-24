package com.ridebooking.booking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.booking.domain.BookingRepository;
import com.ridebooking.booking.domain.BookingStatusChangeRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Full stack against a real MySQL. Authentication is simulated with a ready-made JWT principal, because
 * token issuing and verification belong to auth-service and are tested there; here we test what
 * booking-service does with an already-authenticated caller.
 */
@SpringBootTest(properties = "BOOKING_DB_PASSWORD=overridden-by-testcontainers")
@AutoConfigureMockMvc
@Import(BookingIntegrationTest.Containers.class)
public abstract class BookingIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        MySQLContainer mysql() {
            return new MySQLContainer("mysql:8.4")
                    .withUrlParam("preserveInstants", "true")
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true");
        }
    }

    protected static final String MG_ROAD_TO_KORAMANGALA = """
            {"serviceType":"%s",
             "pickup":{"lat":12.9756,"lng":77.6050,"address":"MG Road"},
             "drop":{"lat":12.9352,"lng":77.6245,"address":"Koramangala"}}""";

    @Autowired protected MockMvc mvc;
    @Autowired protected BookingRepository bookings;
    @Autowired protected BookingStatusChangeRepository history;

    /** Each test starts from an empty database so counts and "open requests" are predictable. */
    @BeforeEach
    void cleanDatabase() {
        history.deleteAll();
        bookings.deleteAll();
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
}
