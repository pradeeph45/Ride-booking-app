package com.ridebooking.auth.infrastructure.otp;

import com.ridebooking.auth.application.otp.OtpProperties;
import com.ridebooking.auth.application.otp.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Emails the code over SMTP. Locally that is Mailpit (inbox at http://localhost:8025); in other
 * environments it is whatever spring.mail.* points to (SES, SendGrid, ...).
 */
@Component
@ConditionalOnProperty(name = "ridebooking.otp.sender", havingValue = "smtp")
public class SmtpOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpOtpSender.class);

    private final JavaMailSender mail;
    private final OtpProperties props;
    private final String from;

    public SmtpOtpSender(JavaMailSender mail, OtpProperties props,
                         @Value("${ridebooking.otp.from:no-reply@ridebooking.local}") String from) {
        this.mail = mail;
        this.props = props;
        this.from = from;
    }

    @Override
    public void send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your Ride Booking App login code");
        message.setText("""
                Your login code is %s

                It expires in %d minutes and can be used once.
                If you did not request this code, you can ignore this email.
                """.formatted(code, props.ttl().toMinutes()));
        try {
            mail.send(message);
        } catch (MailException e) {
            // Swallowed on purpose: failing only for registered emails would reveal which accounts exist.
            // The code is never logged. Operators see the delivery failure and can alert on it.
            log.error("Could not deliver login code email", e);
        }
    }
}
