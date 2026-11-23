package com.ridebooking.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ridebooking.auth.AuthIntegrationTest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Sessions: sid claim, device list, logout, remote revocation, ownership, refresh gating. */
class SessionLifecycleIT extends AuthIntegrationTest {

    private static String claim(String jwt, String name) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        return JsonPath.read(payload, "$." + name);
    }

    private List<String> sessionIds(Login who) throws Exception {
        String body = mvc.perform(get("/users/me/sessions").header("Authorization", bearer(who.access())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    @Test
    void accessTokenCarriesSidAndSessionIsListedAsCurrent() throws Exception {
        Login device = login(registerRider());
        String sid = claim(device.access(), "sid");

        mvc.perform(get("/users/me/sessions").header("Authorization", bearer(device.access())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(sid))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    void refreshKeepsTheSameSession() throws Exception {
        Login device = login(registerRider());
        String sid = claim(device.access(), "sid");

        String body = refresh(device.refresh()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(claim(JsonPath.read(body, "$.access_token"), "sid")).isEqualTo(sid);
    }

    @Test
    void logoutKillsAccessTokenOnTheNextRequestAndTheRefreshToken() throws Exception {
        Login device = login(registerRider());
        mvc.perform(get("/users/me").header("Authorization", bearer(device.access()))).andExpect(status().isOk());

        mvc.perform(post("/auth/logout").header("Authorization", bearer(device.access())))
                .andExpect(status().isNoContent());

        // same, still-unexpired JWT is now refused: revocation is checked on every request
        mvc.perform(get("/users/me").header("Authorization", bearer(device.access())))
                .andExpect(status().isUnauthorized());
        refresh(device.refresh()).andExpect(status().isBadRequest());
    }

    @Test
    void endingAnotherDeviceLeavesThisOneWorking() throws Exception {
        String email = registerRider();
        Login phone = login(email);
        Login laptop = login(email);
        assertThat(sessionIds(phone)).hasSize(2);

        mvc.perform(delete("/users/me/sessions/" + claim(laptop.access(), "sid"))
                        .header("Authorization", bearer(phone.access())))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/me").header("Authorization", bearer(phone.access()))).andExpect(status().isOk());
        mvc.perform(get("/users/me").header("Authorization", bearer(laptop.access()))).andExpect(status().isUnauthorized());
        refresh(laptop.refresh()).andExpect(status().isBadRequest());
        assertThat(sessionIds(phone)).containsExactly(claim(phone.access(), "sid"));
    }

    @Test
    void logOutOfAllDevicesEndsEverySession() throws Exception {
        String email = registerRider();
        Login phone = login(email);
        Login laptop = login(email);

        mvc.perform(delete("/users/me/sessions").header("Authorization", bearer(phone.access())))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/me").header("Authorization", bearer(phone.access()))).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("Authorization", bearer(laptop.access()))).andExpect(status().isUnauthorized());
    }

    @Test
    void cannotEndSomeoneElsesSession() throws Exception {
        Login victim = login(registerRider());
        Login attacker = login(registerRider());

        mvc.perform(delete("/users/me/sessions/" + claim(victim.access(), "sid"))
                        .header("Authorization", bearer(attacker.access())))
                .andExpect(status().isNotFound());

        mvc.perform(get("/users/me").header("Authorization", bearer(victim.access()))).andExpect(status().isOk());
        assertThat(sessionIds(attacker)).doesNotContain(claim(victim.access(), "sid"));
    }

    @Test
    void sessionEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/users/me/sessions").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }
}
