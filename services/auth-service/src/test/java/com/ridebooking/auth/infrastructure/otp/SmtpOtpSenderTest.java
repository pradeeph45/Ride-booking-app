package com.ridebooking.auth.infrastructure.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.ridebooking.auth.application.otp.OtpProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpOtpSenderTest {

    final JavaMailSender mail = Mockito.mock(JavaMailSender.class);
    final OtpProperties props = new OtpProperties(6, Duration.ofMinutes(5), 5, Duration.ofSeconds(60));
    final SmtpOtpSender sender = new SmtpOtpSender(mail, props, "no-reply@ridebooking.local");

    @Test
    void sendsCodeToTheUserWithExpiryAndSafetyNote() {
        sender.send("rider@example.com", "482913");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        SimpleMailMessage msg = sent.getValue();
        assertThat(msg.getTo()).containsExactly("rider@example.com");
        assertThat(msg.getFrom()).isEqualTo("no-reply@ridebooking.local");
        assertThat(msg.getSubject()).doesNotContain("482913"); // subjects show in notifications and logs
        assertThat(msg.getText()).contains("482913").contains("5 minutes").contains("did not request");
    }

    @Test
    void mailFailureNeverPropagates() {
        // If sending threw only for real accounts, the HTTP response would reveal which emails exist.
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));

        assertThatCode(() -> sender.send("rider@example.com", "482913")).doesNotThrowAnyException();
    }
}
