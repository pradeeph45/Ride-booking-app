package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.application.login.LoginAttemptService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/** Feeds password-login outcomes into the lockout counter. Other authentication types are ignored. */
@Component
class LoginAttemptEvents {

    private final LoginAttemptService attempts;

    LoginAttemptEvents(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @EventListener
    void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken) {
            attempts.recordFailure(event.getAuthentication().getName());
        }
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof UsernamePasswordAuthenticationToken
                && !(event.getAuthentication().getPrincipal() instanceof String)) {
            attempts.reset(event.getAuthentication().getName());
        }
    }
}
