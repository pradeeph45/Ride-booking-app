package com.ridebooking.auth.infrastructure.otp;

import com.ridebooking.auth.application.otp.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY: writes the code to the log instead of sending an email.
 * Active in every profile except "prod", where the app fails to start until a real
 * OtpSender (SMTP/SES) bean exists, so codes can never silently leak into prod logs.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "ridebooking.otp.sender", havingValue = "console", matchIfMissing = true)
public class ConsoleOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleOtpSender.class);

    @Override
    public void send(String email, String code) {
        log.warn("DEV-ONLY OTP for {}: {}", mask(email), code);
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
