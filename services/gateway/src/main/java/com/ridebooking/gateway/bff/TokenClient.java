package com.ridebooking.gateway.bff;

import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Talks to the authorization server as the confidential client "rb-bff". */
@Component
public class TokenClient {

    static final String OTP_GRANT = "urn:ridebooking:grant-type:otp";
    static final String GOOGLE_GRANT = "urn:ridebooking:grant-type:google-id-token";

    private final RestClient http;
    private final BffProperties props;

    public TokenClient(RestClient http, BffProperties props) {
        this.http = http;
        this.props = props;
    }

    /** deviceName is the browser's description, so the user's device list is meaningful (not "the gateway"). */
    public TokenResponse otpLogin(String email, String otp, String deviceName) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", OTP_GRANT);
        form.add("email", email);
        form.add("otp", otp);
        if (deviceName != null && !deviceName.isBlank()) {
            form.add("device_name", deviceName);
        }
        return token(form);
    }

    /** Exchanges a Google ID token for our tokens. role only matters if the account is created now. */
    public TokenResponse googleLogin(String idToken, String role, String deviceName) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GOOGLE_GRANT);
        form.add("id_token", idToken);
        if (role != null && !role.isBlank()) {
            form.add("role", role);
        }
        if (deviceName != null && !deviceName.isBlank()) {
            form.add("device_name", deviceName);
        }
        return token(form);
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return token(form);
    }

    public void logout(String accessToken) {
        call(() -> http.post().uri(props.authServiceUrl() + "/auth/logout")
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve().toBodilessEntity());
    }

    private TokenResponse token(MultiValueMap<String, String> form) {
        TokenResponse response = call(() -> http.post().uri(props.authServiceUrl() + "/oauth2/token")
                .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(TokenResponse.class));
        if (response == null || response.accessToken() == null) {
            throw new InvalidCredentialsException();
        }
        return response;
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new InvalidCredentialsException(); // never forward the upstream error body
            }
            throw new UpstreamUnavailableException(e);
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(e);
        }
    }
}
