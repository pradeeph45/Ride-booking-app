package com.ridebooking.tracking.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable) // bearer tokens in a header, no cookies, so nothing for CSRF to ride on
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        // The WebSocket handshake carries no token (browsers cannot add one); the STOMP CONNECT frame
                        // is authenticated by StompAuthInterceptor, and the origin is checked by the endpoint itself.
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/tracking/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> { }))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** Same verification as every other service: signature, issuer, expiry, and the revoked-session list. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${ridebooking.security.jwks-uri}") String jwksUri,
                          @Value("${ridebooking.security.issuer}") String issuer,
                          StringRedisTemplate redis) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new SidRevocationValidator(sid -> Boolean.TRUE.equals(redis.hasKey("revoked:sid:" + sid)))));
        return decoder;
    }
}
