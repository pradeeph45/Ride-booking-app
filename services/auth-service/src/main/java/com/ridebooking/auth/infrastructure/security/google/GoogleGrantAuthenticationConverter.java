package com.ridebooking.auth.infrastructure.security.google;

import com.ridebooking.auth.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/** POST /oauth2/token with grant_type=urn:ridebooking:grant-type:google-id-token and id_token=... */
public class GoogleGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!GoogleGrant.TYPE.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))) {
            return null; // not ours
        }
        String idToken = request.getParameter("id_token");
        if (!StringUtils.hasText(idToken)) {
            throw invalidRequest("id_token is required");
        }
        Role role = Role.RIDER;
        String requested = request.getParameter("role");
        if (StringUtils.hasText(requested)) {
            try {
                role = Role.valueOf(requested.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalidRequest("unknown role");
            }
            if (role == Role.ADMIN) {
                throw invalidRequest("role not allowed"); // never self-assignable
            }
        }
        Set<String> scopes = new LinkedHashSet<>();
        String scope = request.getParameter(OAuth2ParameterNames.SCOPE);
        if (StringUtils.hasText(scope)) {
            scopes.addAll(Arrays.asList(scope.trim().split("\\s+")));
        }
        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        return new GoogleGrantAuthenticationToken(clientPrincipal, idToken, role, scopes);
    }

    private static OAuth2AuthenticationException invalidRequest(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, description, null));
    }
}
