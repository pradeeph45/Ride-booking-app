package com.ridebooking.auth.api;

import com.ridebooking.auth.api.dto.OtpRequest;
import com.ridebooking.auth.application.otp.OtpService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/otp")
public class OtpController {

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    /**
     * Always 202, whether or not the email exists or a resend was throttled, so this endpoint cannot
     * be used to discover accounts. Verification happens at /oauth2/token (OTP grant).
     */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@Valid @RequestBody OtpRequest request) {
        otpService.request(request.email());
    }
}
