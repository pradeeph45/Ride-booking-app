package com.ridebooking.driver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.driver.DriverIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class DriverApiIT extends DriverIntegrationTest {

    final UUID dave = UUID.randomUUID();
    final UUID erin = UUID.randomUUID();

    // ---- who may call what ---------------------------------------------------------------------

    @Test
    void requestsWithoutATokenAreRejected() throws Exception {
        mvc.perform(get("/drivers/me").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/drivers/nearby").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    void ridersCannotUseDriverEndpoints() throws Exception {
        mvc.perform(get("/drivers/me").with(rider(dave))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/drivers/me/online").with(rider(dave)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theInternalDoorOpensOnlyForServiceTokensWithTheRightScope() throws Exception {
        String url = "/internal/drivers/nearby?lat=12.97&lng=77.6&type=CAR_MINI";

        mvc.perform(get(url).with(service("drivers.read"))).andExpect(status().isOk());
        mvc.perform(get(url).with(service("booking.read"))).andExpect(status().isForbidden());   // wrong scope
        mvc.perform(get(url).with(driver(dave))).andExpect(status().isForbidden());              // a driver is not a service
        mvc.perform(get(url).with(rider(dave))).andExpect(status().isForbidden());
    }

    @Test
    void aServiceTokenCannotActAsADriver() throws Exception {
        mvc.perform(get("/drivers/me").with(service("drivers.read"))).andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreClosedByDefault() throws Exception {
        mvc.perform(get("/drivers-admin/everything").with(driver(dave))).andExpect(status().isForbidden());
    }

    // ---- profile ------------------------------------------------------------------------------

    @Test
    void registersAVehicleThenUpdatesIt() throws Exception {
        register(dave, "CAR_MINI", "ka-01-ab-1234", "Swift")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plateNumber").value("KA01AB1234"))
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        register(dave, "CAR_SEDAN", "KA 01 AB 1234", "Dzire")                       // same plate (spelled differently), new model and type
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("CAR_SEDAN"))
                .andExpect(jsonPath("$.vehicleModel").value("Dzire"));

        mvc.perform(get("/drivers/me").with(driver(dave))).andExpect(status().isOk()).andExpect(jsonPath("$.vehicleType").value("CAR_SEDAN"));
        assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void profileLookupBeforeRegisteringIsA404WithAHelpfulCode() throws Exception {
        mvc.perform(get("/drivers/me").with(driver(dave)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRIVER_PROFILE_REQUIRED"));
    }

    @Test
    void driversOnlyEverSeeTheirOwnProfile() throws Exception {
        register(dave, "BIKE", "KA01AB1111", "Honda");
        register(erin, "AUTO", "KA01AB2222", "Bajaj");

        mvc.perform(get("/drivers/me").with(driver(dave))).andExpect(jsonPath("$.plateNumber").value("KA01AB1111"));
        mvc.perform(get("/drivers/me").with(driver(erin))).andExpect(jsonPath("$.plateNumber").value("KA01AB2222"));
    }

    @Test
    void rejectsInvalidProfiles() throws Exception {
        register(dave, "CAR_MINI", "x", "Swift").andExpect(status().isBadRequest());                 // plate too short
        register(dave, "CAR_MINI", "KA01<script>", "Swift").andExpect(status().isBadRequest());
        register(dave, "CAR_MINI", "KA01AB1234", " ").andExpect(status().isBadRequest());             // no model
        register(dave, "HELICOPTER", "KA01AB1234", "Swift").andExpect(status().isBadRequest());       // unknown type
        assertThat(profiles.count()).isZero();
    }

    @Test
    void twoDriversCannotRegisterTheSameVehicle() throws Exception {
        register(dave, "CAR_MINI", "KA01AB1234", "Swift").andExpect(status().isCreated());

        register(erin, "CAR_MINI", "ka 01 ab 1234", "Swift")                                          // same plate, different spelling
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLATE_ALREADY_REGISTERED"));
        assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void aDoubleSubmittedFirstRegistrationCreatesOneProfileAndNeverFails() throws Exception {
        int threads = 12;
        List<Integer> statuses = runTogether(IntStream.range(0, threads).<Callable<Integer>>mapToObj(i -> () ->
                register(dave, "CAR_MINI", "KA01AB1234", "Swift").andReturn().getResponse().getStatus()).toList());

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);       // one created it
        assertThat(statuses).containsOnly(200, 201);                          // the rest saw an update: no 409, no 500
        assertThat(profiles.count()).isEqualTo(1);
    }

    @Test
    void twoDriversRacingForTheSamePlateYieldExactlyOneWinner() throws Exception {
        List<Integer> statuses = runTogether(List.of(
                () -> register(dave, "CAR_MINI", "KA09ZZ9999", "Swift").andReturn().getResponse().getStatus(),
                () -> register(erin, "CAR_MINI", "KA09ZZ9999", "Swift").andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(profiles.count()).isEqualTo(1);
    }

    // ---- going online / location -------------------------------------------------------------

    @Test
    void goingOnlineNeedsAProfileAndIsRepeatSafe() throws Exception {
        act(dave, "/drivers/me/online").andExpect(status().isNotFound());                     // no vehicle yet

        register(dave, "CAR_MINI", "KA01AB1234", "Swift");
        act(dave, "/drivers/me/online").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ONLINE"));
        act(dave, "/drivers/me/online").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ONLINE"));
        act(dave, "/drivers/me/offline").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFFLINE"));
        act(dave, "/drivers/me/offline").andExpect(status().isOk());
    }

    @Test
    void locationIsOnlyAcceptedWhileOnline() throws Exception {
        register(dave, "CAR_MINI", "KA01AB1234", "Swift");

        ping(dave, 12.9756, 77.6050).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_OFFLINE"));
        act(dave, "/drivers/me/online");
        ping(dave, 12.9756, 77.6050).andExpect(status().isNoContent());
    }

    @Test
    void invalidCoordinatesAreRejected() throws Exception {
        register(dave, "CAR_MINI", "KA01AB1234", "Swift");
        act(dave, "/drivers/me/online");

        ping(dave, 95, 77.6).andExpect(status().isBadRequest());
        ping(dave, 12.9, 190).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/drivers/me/location").with(driver(dave))
                .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":12.9,\"lng\":77.6,\"heading\":400}")).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/drivers/me/location").with(driver(dave))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void aRunawayClientIsThrottledToOnePingPerInterval() throws Exception {
        register(dave, "CAR_MINI", "KA01AB1234", "Swift");
        act(dave, "/drivers/me/online");

        ping(dave, 12.9756, 77.6050).andExpect(status().isNoContent());
        ping(dave, 12.9757, 77.6051).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_UPDATES"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "1"));

        Thread.sleep(450);                                                                      // interval is 400 ms in tests
        ping(dave, 12.9757, 77.6051).andExpect(status().isNoContent());
    }

    @Test
    void aThrottledPingDoesNotMoveTheDriver() throws Exception {
        UUID d = liveDriver("CAR_MINI", "KA01AB1234", 12.9756, 77.6050);

        ping(d, 13.5, 77.0).andExpect(status().isTooManyRequests());                            // refused

        nearby("CAR_MINI", 5).andExpect(jsonPath("$[0].lat").value(org.hamcrest.Matchers.closeTo(12.9756, 0.0001))); // still where the accepted ping put them (Redis keeps ~1 m precision)
    }

    // ---- nearby (what matching will call) -----------------------------------------------------

    @Test
    void nearbyReturnsLiveDriversOfTheTypeNearestFirst() throws Exception {
        UUID far = liveDriver("CAR_MINI", "KA01AA0001", 12.9352, 77.6245);
        UUID near = liveDriver("CAR_MINI", "KA01AA0002", 12.9760, 77.6055);
        liveDriver("BIKE", "KA01AA0003", 12.9758, 77.6052);                                     // wrong type

        nearby("CAR_MINI", 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].driverId").value(near.toString()))
                .andExpect(jsonPath("$[1].driverId").value(far.toString()))
                .andExpect(jsonPath("$[0].distanceKm").isNumber());
    }

    @Test
    void offlineDriversAreNotFound() throws Exception {
        UUID d = liveDriver("CAR_MINI", "KA01AB1234", 12.9760, 77.6055);
        nearby("CAR_MINI", 10).andExpect(jsonPath("$.length()").value(1));

        act(d, "/drivers/me/offline");

        nearby("CAR_MINI", 10).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void silentDriversDropOutAfterTheHeartbeatTimeout() throws Exception {
        liveDriver("CAR_MINI", "KA01AB1234", 12.9760, 77.6055);

        clock.advance(Duration.ofSeconds(20));
        nearby("CAR_MINI", 10).andExpect(jsonPath("$.length()").value(1));                      // still within 30 s

        clock.advance(Duration.ofSeconds(15));                                                  // 35 s of silence
        nearby("CAR_MINI", 10).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void changingVehicleTypeMovesTheDriverToTheNewServiceSet() throws Exception {
        UUID d = liveDriver("CAR_MINI", "KA01AB1234", 12.9760, 77.6055);

        register(d, "CAR_SUV", "KA01AB1234", "Innova").andExpect(status().isOk());

        nearby("CAR_MINI", 10).andExpect(jsonPath("$.length()").value(0));                      // gone from the old set at once
        Thread.sleep(450);
        ping(d, 12.9760, 77.6055).andExpect(status().isNoContent());                            // the next ping re-adds them
        nearby("CAR_SUV", 10).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void nearbyValidatesAndCapsItsInputs() throws Exception {
        mvc.perform(get("/internal/drivers/nearby").with(service("drivers.read")).param("lat", "95").param("lng", "77").param("type", "BIKE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/drivers/nearby").with(service("drivers.read")).param("lat", "12.9").param("lng", "77").param("type", "TANK"))
                .andExpect(status().isBadRequest());
        nearby("BIKE", 100000).andExpect(status().isOk());                                      // radius is capped, not an error
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().<Callable<T>>map(t -> () -> {
                start.await();
                return t.call();
            }).map(pool::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        }
    }
}
