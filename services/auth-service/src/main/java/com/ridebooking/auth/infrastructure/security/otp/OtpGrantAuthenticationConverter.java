package com.ridebooking.auth.infrastructure.security.otp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/** Turns POST /oauth2/token?grant_type=urn:ridebooking:grant-type:otp into an OtpGrantAuthenticationToken. */
public class OtpGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!OtpGrant.TYPE.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))) {
            return null; // not ours: let another converter handle it
        }
        String email = request.getParameter("email");
        String otp = request.getParameter("otp");
        if (!StringUtils.hasText(email) || !StringUtils.hasText(otp)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_REQUEST, "email and otp are required", null));
        }
        Set<String> scopes = new LinkedHashSet<>();
        String scope = request.getParameter(OAuth2ParameterNames.SCOPE);
        if (StringUtils.hasText(scope)) {
            scopes.addAll(Arrays.asList(scope.trim().split("\\s+")));
        }
        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        return new OtpGrantAuthenticationToken(clientPrincipal, email, otp, scopes);
    }
}
