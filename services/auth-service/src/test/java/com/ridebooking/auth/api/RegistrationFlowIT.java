package com.ridebooking.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.auth.domain.UserRepository;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Full stack: HTTP -> controller -> service -> JPA -> real MySQL. */
class RegistrationFlowIT extends com.ridebooking.auth.AuthIntegrationTest {


    @Autowired UserRepository users;

    private static String body(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"S3cret-pass!\",\"role\":\"RIDER\"}";
    }

    @Test
    void registersThenRejectsSameEmailIgnoringCase() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("flow@me.com")))
                .andExpect(status().isCreated());
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("FLOW@me.com")))
                .andExpect(status().isConflict());

        assertThat(users.findByEmail("flow@me.com")).isPresent()
                .get().satisfies(u -> assertThat(u.getPasswordHash()).startsWith("$2"));
    }

    @Test
    void concurrentRegistrationsOfSameEmailYieldExactlyOneUser() throws Exception {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            java.util.List<Future<Integer>> results = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(body("race@me.com"))).andReturn().getResponse().getStatus();
                    })
                    .map(pool::submit).toList();
            start.countDown();

            long created = 0, conflicts = 0;
            for (Future<Integer> f : results) {
                int s = f.get();
                if (s == 201) created++;
                if (s == 409) conflicts++;
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        }
    }

    /** Case and surrounding spaces must not be a way around the uniqueness rule. */
    @Test
    void concurrentRegistrationsOfCaseAndWhitespaceVariantsYieldExactlyOneUser() throws Exception {
        String[] variants = {"Variant@Me.com", "variant@me.com", "VARIANT@ME.COM", " variant@me.com ", "vArIaNt@mE.cOm"};
        int threads = 25;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            java.util.List<Future<Integer>> results = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(body(variants[i % variants.length]))).andReturn().getResponse().getStatus();
                    })
                    .map(pool::submit).toList();
            start.countDown();

            // Padded variants (" variant@me.com ") fail bean validation with 400 before reaching the
            // uniqueness check; every other variant must lose the race with 409. Only one may win.
            long created = 0, conflicts = 0, invalid = 0;
            for (Future<Integer> f : results) {
                int s = f.get();
                if (s == 201) created++;
                else if (s == 409) conflicts++;
                else if (s == 400) invalid++;
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts + invalid).isEqualTo(threads - 1);
            assertThat(invalid).isEqualTo(threads / variants.length); // exactly the padded variant
            assertThat(users.findAll().stream().filter(u -> u.getEmail().equals("variant@me.com"))).hasSize(1);
        }
    }
}
