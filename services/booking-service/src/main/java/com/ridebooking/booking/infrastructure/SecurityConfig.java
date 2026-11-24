package com.ridebooking.booking.infrastructure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * booking-service never issues or stores credentials. It trusts only tokens signed by auth-service
 * (verified with its public JWKS), issued by the expected issuer, not expired, and not revoked.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable) // bearer tokens in a header, no cookies, so nothing for CSRF to ride on
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        // rider-only
                        .requestMatchers(HttpMethod.POST, "/bookings", "/bookings/estimate").hasRole("RIDER")
                        .requestMatchers(HttpMethod.POST, "/bookings/*/cancel").hasRole("RIDER")
                        // driver-only
                        .requestMatchers(HttpMethod.GET, "/bookings/open").hasRole("DRIVER")
                        .requestMatchers(HttpMethod.POST, "/bookings/*/accept", "/bookings/*/arrive",
                                "/bookings/*/start", "/bookings/*/complete").hasRole("DRIVER")
                        // everything else (view one booking, history) just needs a valid user token
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${ridebooking.security.jwks-uri}") String jwksUri,
                          @Value("${ridebooking.security.issuer}") String issuer,
                          StringRedisTemplate redis) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), // signature is checked by the decoder; this adds iss + exp + nbf
                new SidRevocationValidator(sid -> Boolean.TRUE.equals(redis.hasKey("revoked:sid:" + sid)))));
        return decoder;
    }

    /** scope claim -> SCOPE_x, role claim -> ROLE_x */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            return List.copyOf(authorities);
        });
        return converter;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
