package com.ridebooking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.auth.AuthIntegrationTest;
import com.ridebooking.auth.domain.UserIdentity;
import com.ridebooking.auth.domain.UserIdentityRepository;
import com.ridebooking.auth.domain.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Sign in with Google over HTTP, with a fake Google behind the verifier port. */
class GoogleLoginIT extends AuthIntegrationTest {

    @Autowired UserRepository users;
    @Autowired UserIdentityRepository identities;

    private static String uniqueEmail() {
        return "g-" + UUID.randomUUID() + "@gmail.com";
    }

    private String accessTokenOf(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private String me(String accessToken, String field) throws Exception {
        String body = mvc.perform(get("/users/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$." + field);
    }

    private void formLogin(String email, String password, String expectedRedirect) throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect));
    }

    @Test
    void firstGoogleLoginCreatesAnAccountAndIssuesWorkingTokens() throws Exception {
        String email = uniqueEmail();

        String token = accessTokenOf(googleExchange(googleToken("sub-" + UUID.randomUUID(), email), null));

        assertThat(me(token, "email")).isEqualTo(email);
        assertThat(me(token, "role")).isEqualTo("RIDER"); // default role
        assertThat(users.findByEmail(email)).get().satisfies(u -> {
            assertThat(u.getPasswordHash()).isNull();
            assertThat(u.isEmailVerified()).isTrue();
        });
    }

    @Test
    void newUserCanSignUpAsDriver() throws Exception {
        String token = accessTokenOf(googleExchange(googleToken("sub-" + UUID.randomUUID(), uniqueEmail()), "DRIVER"));

        assertThat(me(token, "role")).isEqualTo("DRIVER");
    }

    @Test
    void sameGoogleAccountAlwaysReachesTheSameUserEvenIfItsEmailChanges() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String first = accessTokenOf(googleExchange(googleToken(sub, uniqueEmail()), null));
        String second = accessTokenOf(googleExchange(googleToken(sub, uniqueEmail()), null)); // email changed at Google

        assertThat(me(second, "id")).isEqualTo(me(first, "id"));
        mvc.perform(get("/users/me/sessions").header("Authorization", bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)); // two logins = two devices
    }

    @Test
    void googleTokensGetFullSessionBehaviour() throws Exception {
        String body = googleExchange(googleToken("sub-" + UUID.randomUUID(), uniqueEmail()), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.access_token");
        String refresh = JsonPath.read(body, "$.refresh_token");

        refresh(refresh).andExpect(status().isOk()); // refresh works

        mvc.perform(post("/auth/logout").header("Authorization", bearer(access))).andExpect(status().isNoContent());
        mvc.perform(get("/users/me").header("Authorization", bearer(access))).andExpect(status().isUnauthorized());
    }

    @Test
    void existingVerifiedAccountIsLinkedAndKeepsItsOtherLoginMethods() throws Exception {
        String email = registerRider();
        login(email); // OTP login proves the mailbox, so the account becomes verified
        assertThat(users.findByEmail(email)).get().satisfies(u -> assertThat(u.isEmailVerified()).isTrue());

        String token = accessTokenOf(googleExchange(googleToken("sub-" + UUID.randomUUID(), email), null));

        assertThat(me(token, "email")).isEqualTo(email);
        formLogin(email, "S3cret-pass!", "/"); // password still works: the owner was already proven
    }

    @Test
    void googleLoginTakesOverAnUnverifiedPasswordAccountSoSquattersLoseAccess() throws Exception {
        // A squatter registers the victim's address with a password they chose. Nobody verified it.
        String victimEmail = registerRider();
        formLogin(victimEmail, "S3cret-pass!", "/"); // squatter can log in...

        // ...until the real owner signs in with Google, which proves the address.
        String token = accessTokenOf(googleExchange(googleToken("sub-" + UUID.randomUUID(), victimEmail), null));

        assertThat(me(token, "email")).isEqualTo(victimEmail);
        formLogin(victimEmail, "S3cret-pass!", "/login?error"); // squatter's password is dead
        assertThat(users.findByEmail(victimEmail)).get().satisfies(u -> assertThat(u.getPasswordHash()).isNull());
    }

    @Test
    void otpLoginMarksTheEmailVerified() throws Exception {
        String email = registerRider();
        assertThat(users.findByEmail(email)).get().satisfies(u -> assertThat(u.isEmailVerified()).isFalse());

        login(email);

        assertThat(users.findByEmail(email)).get().satisfies(u -> assertThat(u.isEmailVerified()).isTrue());
    }

    @Test
    void rejectsInvalidGoogleTokensWithAGenericError() throws Exception {
        googleExchange("forged-token", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void adminCannotBeSelfAssignedThroughGoogle() throws Exception {
        googleExchange(googleToken("sub-" + UUID.randomUUID(), uniqueEmail()), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        googleExchange(googleToken("sub-" + UUID.randomUUID(), uniqueEmail()), "WIZARD")
                .andExpect(status().isBadRequest());
    }

    @Test
    void idTokenIsRequired() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-bff", BFF_SECRET)).param("grant_type", GOOGLE_GRANT))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheConfidentialClientMayUseTheGoogleGrant() throws Exception {
        mvc.perform(post("/oauth2/token").with(httpBasic("rb-bff", "wrong-secret"))
                        .param("grant_type", GOOGLE_GRANT).param("id_token", googleToken("s", uniqueEmail())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth2/token").accept(MediaType.APPLICATION_JSON).param("client_id", "rb-web")
                        .param("grant_type", GOOGLE_GRANT).param("id_token", googleToken("s", uniqueEmail())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentFirstLoginsCreateExactlyOneUserAndOneIdentity() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        String email = uniqueEmail();
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> results = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return googleExchange(googleToken(sub, email), null).andReturn().getResponse().getStatus();
                    })
                    .map(pool::submit).toList();
            start.countDown();

            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(200); // nobody sees a 500 or a conflict: losers retry and succeed
            }
        }
        assertThat(users.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
        assertThat(identities.findByProviderAndSubject(UserIdentity.GOOGLE, sub)).isPresent();
    }
}
