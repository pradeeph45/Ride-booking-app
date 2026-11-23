package com.ridebooking.auth.infrastructure.google;

import com.ridebooking.auth.application.google.GoogleIdentityVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
class GoogleConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleConfig.class);

    /**
     * Only the OAuth client ID is needed (it is public, not a secret). With it unset, the verifier
     * rejects every token, so Google login is simply off rather than half-working.
     */
    @Bean
    GoogleIdentityVerifier googleIdentityVerifier(@Value("${ridebooking.google.client-id:}") String clientId) {
        if (clientId == null || clientId.isBlank()) {
            log.warn("GOOGLE_CLIENT_ID is not set: Sign in with Google is disabled");
        }
        return GoogleIdTokenVerifier.forGoogle(clientId);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
