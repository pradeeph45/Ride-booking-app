package com.ridebooking.auth.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridebooking.auth.api.error.GlobalExceptionHandler;
import com.ridebooking.auth.application.otp.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OtpController.class)
@AutoConfigureMockMvc(addFilters = false) // security rules are covered by OtpLoginIT
@Import(GlobalExceptionHandler.class)
class OtpControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OtpService otpService;

    @Test
    void validRequestReturns202() throws Exception {
        mvc.perform(post("/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isAccepted());
        verify(otpService).request("a@b.com");
    }

    @Test
    void invalidEmailReturns400AndSendsNothing() throws Exception {
        mvc.perform(post("/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(otpService);
    }
}
