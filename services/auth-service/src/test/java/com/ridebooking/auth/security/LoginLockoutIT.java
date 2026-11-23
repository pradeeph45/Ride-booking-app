package com.ridebooking.auth.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.auth.AuthIntegrationTest;
import org.junit.jupiter.api.Test;

/** Password (form) login: 5 failures lock the account, a success before that resets the counter. */
class LoginLockoutIT extends AuthIntegrationTest {

    private void attempt(String email, String password, String expectedRedirect) throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect));
    }

    @Test
    void fiveFailuresLockTheAccountEvenForTheCorrectPassword() throws Exception {
        String email = registerRider();
        for (int i = 0; i < 5; i++) {
            attempt(email, "wrong-password", "/login?error");
        }

        attempt(email, "S3cret-pass!", "/login?error"); // locked: right password no longer works
    }

    @Test
    void successBeforeTheLimitResetsTheCounter() throws Exception {
        String email = registerRider();
        for (int i = 0; i < 4; i++) {
            attempt(email, "wrong-password", "/login?error");
        }
        attempt(email, "S3cret-pass!", "/");           // 5th try is correct: allowed, counter reset
        for (int i = 0; i < 4; i++) {
            attempt(email, "wrong-password", "/login?error");
        }

        attempt(email, "S3cret-pass!", "/");           // still fewer than 5 consecutive failures
    }

    @Test
    void lockoutIsPerAccount() throws Exception {
        String locked = registerRider();
        String other = registerRider();
        for (int i = 0; i < 5; i++) {
            attempt(locked, "wrong-password", "/login?error");
        }

        attempt(other, "S3cret-pass!", "/");
    }

    @Test
    void unknownUsersAreCountedToo() throws Exception {
        for (int i = 0; i < 6; i++) {
            attempt("nobody@example.com", "whatever-pass", "/login?error"); // never reveals existence
        }
    }
}
