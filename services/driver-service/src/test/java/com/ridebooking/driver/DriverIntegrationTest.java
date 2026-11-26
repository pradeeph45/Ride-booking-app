package com.ridebooking.driver;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ridebooking.driver.domain.DriverProfileRepository;
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
 * Full stack against real MySQL and Redis. Authentication is simulated with a ready-made JWT principal:
 * token issuing and verification belong to auth-service and are tested there.
 */
@SpringBootTest(properties = {
        "DRIVER_DB_PASSWORD=overridden-by-testcontainers",
        "ridebooking.presence.sweeper=false",            // the sweep is triggered by hand in tests
        "ridebooking.presence.min-ping-interval=400ms"})
@AutoConfigureMockMvc
@Import(DriverIntegrationTest.Infrastructure.class)
public abstract class DriverIntegrationTest {

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

        /** A clock the tests can move, so heartbeat expiry needs no sleeping. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    protected static final String PROFILE = """
            {"vehicleType":"%s","plateNumber":"%s","vehicleModel":"%s"}""";

    @Autowired protected MockMvc mvc;
    @Autowired protected DriverProfileRepository profiles;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanState() {
        profiles.deleteAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected static RequestPostProcessor driver(UUID id) {
        return user(id, "DRIVER");
    }

    protected static RequestPostProcessor rider(UUID id) {
        return user(id, "RIDER");
    }

    /** A service token (client credentials): a scope, no user id, no role. */
    protected static RequestPostProcessor service(String scope) {
        return jwt().jwt(j -> j.subject("rb-booking")).authorities(new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private static RequestPostProcessor user(UUID id, String role) {
        return jwt()
                .jwt(j -> j.claim("uid", id.toString()).claim("role", role).claim("sid", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected ResultActions register(UUID driverId, String type, String plate, String model) throws Exception {
        return mvc.perform(put("/drivers/me").with(driver(driverId)).contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.formatted(type, plate, model)));
    }

    protected ResultActions act(UUID driverId, String path) throws Exception {
        return mvc.perform(post(path).with(driver(driverId)));
    }

    protected ResultActions ping(UUID driverId, double lat, double lng) throws Exception {
        return mvc.perform(post("/drivers/me/location").with(driver(driverId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lat\":" + lat + ",\"lng\":" + lng + "}"));
    }

    protected ResultActions nearby(String type, double radiusKm) throws Exception {
        return mvc.perform(get("/internal/drivers/nearby").with(service("drivers.read"))
                .param("lat", "12.9756").param("lng", "77.6050").param("type", type).param("radiusKm", String.valueOf(radiusKm)));
    }

    /** Registers a driver, puts them online and sends one location. */
    protected UUID liveDriver(String type, String plate, double lat, double lng) throws Exception {
        UUID id = UUID.randomUUID();
        register(id, type, plate, "Test vehicle");
        act(id, "/drivers/me/online");
        ping(id, lat, lng);
        return id;
    }
}
