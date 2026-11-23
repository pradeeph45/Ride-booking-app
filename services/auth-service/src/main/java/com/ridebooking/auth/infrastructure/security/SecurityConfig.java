package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.application.google.GoogleIdentityVerifier;
import com.ridebooking.auth.application.google.GoogleLoginService;
import com.ridebooking.auth.application.otp.OtpService;
import com.ridebooking.auth.infrastructure.security.google.GoogleGrantAuthenticationConverter;
import com.ridebooking.auth.infrastructure.security.google.GoogleGrantAuthenticationProvider;
import com.ridebooking.auth.domain.UserRepository;
import com.ridebooking.auth.infrastructure.security.otp.OtpGrantAuthenticationConverter;
import com.ridebooking.auth.infrastructure.security.otp.OtpGrantAuthenticationProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
class SecurityConfig {

    /** Chain 1: OAuth2/OIDC protocol endpoints (/oauth2/**, /.well-known/**, jwks). */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http,
                                                 OtpService otpService,
                                                 UserRepository users,
                                                 GoogleIdentityVerifier googleVerifier,
                                                 GoogleLoginService googleLoginService,
                                                 OAuth2AuthorizationService authorizationService,
                                                 OAuth2TokenGenerator<?> tokenGenerator) throws Exception {
        OAuth2AuthorizationServerConfigurer server = new OAuth2AuthorizationServerConfigurer();
        UserTokenIssuer issuer = new UserTokenIssuer(authorizationService, tokenGenerator);
        http
                .securityMatcher(server.getEndpointsMatcher())
                .with(server, s -> s
                        .tokenGenerator(tokenGenerator)
                        .oidc(Customizer.withDefaults())
                        .tokenEndpoint(token -> token
                                // custom grants: passwordless OTP, and Sign in with Google
                                .accessTokenRequestConverter(new OtpGrantAuthenticationConverter())
                                .accessTokenRequestConverter(new GoogleGrantAuthenticationConverter())
                                .authenticationProvider(new OtpGrantAuthenticationProvider(otpService, users, issuer))
                                .authenticationProvider(new GoogleGrantAuthenticationProvider(
                                        googleVerifier, googleLoginService, issuer))))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                // Browsers hitting /oauth2/authorize unauthenticated are sent to the login form.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Chain 2: our own API (register, OTP request, profile) plus the login form. */
    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/auth/register"),
                                PathPatternRequestMatcher.withDefaults().matcher("/auth/otp/request"),
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health"),
                                PathPatternRequestMatcher.withDefaults().matcher("/error")).permitAll()
                        .anyRequest().authenticated())
                // Public JSON endpoints carry no cookies, so CSRF applies only to the browser login form.
                .csrf(csrf -> csrf.ignoringRequestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/auth/**")))
                .formLogin(Customizer.withDefaults())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    /** Maps the token's scopes to SCOPE_* and its custom "role" claim to ROLE_*. */
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
}
