package com.ridebooking.gateway.bff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BffController.class)
@EnableConfigurationProperties(BffProperties.class)
@Import(BffExceptionHandler.class)
@TestPropertySource(properties = {
        "ridebooking.gateway.auth-service-url=http://auth",
        "ridebooking.gateway.client-id=rb-bff",
        "ridebooking.gateway.client-secret=s3cr3t",
        "ridebooking.gateway.cookie-secure=false",
        "ridebooking.gateway.refresh-cookie-name=rb_refresh",
        "ridebooking.gateway.allowed-origin=http://localhost:5173"})
class BffControllerTest {

    static final String CSRF = "X-RB-CSRF";

    @Autowired MockMvc mvc;
    @MockitoBean TokenClient tokens;

    private static final String LOGIN = "{\"email\":\"a@b.com\",\"otp\":\"123456\"}";

    @Test
    void loginReturnsAccessTokenInBodyAndRefreshOnlyInHardenedCookie() throws Exception {
        when(tokens.otpLogin("a@b.com", "123456", "TestBrowser/1.0")).thenReturn(new TokenResponse("AT", "RT", 899));

        mvc.perform(post("/bff/login").header(CSRF, "1").header("User-Agent", "TestBrowser/1.0")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("AT"))
                .andExpect(jsonPath("$.expiresIn").value(899))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("RT"))))
                .andExpect(header().string("Set-Cookie", Matchers.allOf(
                        Matchers.containsString("rb_refresh=RT"),
                        Matchers.containsString("HttpOnly"),
                        Matchers.containsString("SameSite=Strict"),
                        Matchers.containsString("Path=/bff"))));
    }

    @Test
    void googleLoginIssuesSessionTheSameWayAsOtpLogin() throws Exception {
        when(tokens.googleLogin("GOOGLE.JWT", "DRIVER", "TestBrowser/1.0")).thenReturn(new TokenResponse("AT", "RT", 899));

        mvc.perform(post("/bff/google").header(CSRF, "1").header("User-Agent", "TestBrowser/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"GOOGLE.JWT\",\"role\":\"DRIVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("AT"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", Matchers.allOf(
                        Matchers.containsString("rb_refresh=RT"), Matchers.containsString("HttpOnly"))));
    }

    @Test
    void googleLoginRoleIsOptionalButNeverAdmin() throws Exception {
        when(tokens.googleLogin("GOOGLE.JWT", null, null)).thenReturn(new TokenResponse("AT", "RT", 899));

        mvc.perform(post("/bff/google").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"GOOGLE.JWT\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/bff/google").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"GOOGLE.JWT\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void googleLoginNeedsCsrfHeaderAndAToken() throws Exception {
        mvc.perform(post("/bff/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/google").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectedGoogleTokenIs401WithGenericCode() throws Exception {
        when(tokens.googleLogin(any(), any(), any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/bff/google").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"forged\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void requestsWithoutTheCsrfHeaderAreRefused() throws Exception {
        mvc.perform(post("/bff/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/refresh").cookie(new Cookie("rb_refresh", "RT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void badOtpIs401WithGenericCode() throws Exception {
        when(tokens.otpLogin(any(), any(), any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/bff/login").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void invalidLoginPayloadIs400() throws Exception {
        mvc.perform(post("/bff/login").header(CSRF, "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\",\"otp\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshRotatesTheCookie() throws Exception {
        when(tokens.refresh("RT")).thenReturn(new TokenResponse("AT2", "RT2", 899));

        mvc.perform(post("/bff/refresh").header(CSRF, "1").cookie(new Cookie("rb_refresh", "RT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("AT2"))
                .andExpect(header().string("Set-Cookie", Matchers.containsString("rb_refresh=RT2")));
    }

    @Test
    void refreshWithoutCookieIs401() throws Exception {
        mvc.perform(post("/bff/refresh").header(CSRF, "1")).andExpect(status().isUnauthorized());
    }

    @Test
    void failedRefreshClearsTheCookie() throws Exception {
        when(tokens.refresh("BAD")).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/bff/refresh").header(CSRF, "1").cookie(new Cookie("rb_refresh", "BAD")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", Matchers.containsString("Max-Age=0")));
    }

    @Test
    void logoutEndsSessionAndClearsCookie() throws Exception {
        mvc.perform(post("/bff/logout").header(CSRF, "1").header("Authorization", "Bearer AT"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", Matchers.containsString("Max-Age=0")));
        verify(tokens).logout("AT");
    }
}
