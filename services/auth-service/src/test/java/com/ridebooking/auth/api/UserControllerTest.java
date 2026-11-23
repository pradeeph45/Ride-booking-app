package com.ridebooking.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.auth.api.error.GlobalExceptionHandler;
import com.ridebooking.auth.api.mapper.UserMapper;
import com.ridebooking.auth.application.UserService;
import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.DuplicateEmailException;
import com.ridebooking.auth.domain.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false) // security rules are covered by OtpLoginIT
@Import({UserMapper.class, GlobalExceptionHandler.class, UserControllerTest.Config.class})
class UserControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        PasswordHasher passwordHasher() {
            return new BcryptPasswordHasher(new BCryptPasswordEncoder(4));
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean UserService service;

    private static final String VALID = """
            {"email":"Rider@Example.com","password":"S3cret-pass!","role":"RIDER"}""";

    @Test
    void registerReturns201AndNeverEchoesPassword() throws Exception {
        when(service.register(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("rider@example.com"))
                .andExpect(jsonPath("$.role").value("RIDER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("S3cret"))));
    }

    @Test
    void invalidPayloadReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"password\":\"short\",\"role\":\"RIDER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void adminRoleCannotBeSelfAssigned() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"S3cret-pass!\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailReturns409() throws Exception {
        when(service.register(any())).thenThrow(new DuplicateEmailException("rider@example.com"));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternals() throws Exception {
        when(service.register(any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
