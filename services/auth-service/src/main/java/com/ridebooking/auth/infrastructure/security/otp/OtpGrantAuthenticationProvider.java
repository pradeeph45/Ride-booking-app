package com.ridebooking.auth.infrastructure.security.otp;

import com.ridebooking.auth.application.otp.OtpService;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import com.ridebooking.auth.infrastructure.security.UserTokenIssuer;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

/** Verifies the emailed OTP, then hands over to the shared issuer. */
public class OtpGrantAuthenticationProvider implements AuthenticationProvider {

    private final OtpService otpService;
    private final UserRepository users;
    private final UserTokenIssuer issuer;

    public OtpGrantAuthenticationProvider(OtpService otpService, UserRepository users, UserTokenIssuer issuer) {
        this.otpService = otpService;
        this.users = users;
        this.issuer = issuer;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OtpGrantAuthenticationToken request = (OtpGrantAuthenticationToken) authentication;

        OAuth2ClientAuthenticationToken clientAuth = issuer.authenticatedClient(request.getPrincipal(), OtpGrant.TYPE);
        Set<String> scopes = issuer.authorizedScopes(clientAuth, request.getScopes());

        // One generic error for wrong code, expired code, unknown or disabled user: no oracle for attackers.
        if (!otpService.verify(request.getEmail(), request.getOtp())) {
            throw UserTokenIssuer.error(OAuth2ErrorCodes.INVALID_GRANT);
        }
        User user = users.findByEmail(request.getEmail().trim().toLowerCase(Locale.ROOT))
                .filter(User::isEnabled)
                .orElseThrow(() -> UserTokenIssuer.error(OAuth2ErrorCodes.INVALID_GRANT));

        if (!user.isEmailVerified()) {
            // The code was delivered to this address and just came back: ownership is proven.
            user.markEmailVerified();
            users.save(user);
        }

        UUID sid = UUID.randomUUID();
        return issuer.issue(clientAuth, OtpGrant.TYPE, user, scopes, sid, request.withSessionId(sid));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OtpGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
