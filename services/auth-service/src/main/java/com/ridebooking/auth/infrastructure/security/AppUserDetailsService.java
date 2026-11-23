package com.ridebooking.auth.infrastructure.security;

import com.ridebooking.auth.application.login.LoginAttemptService;
import com.ridebooking.auth.domain.UserRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Bridges our User aggregate to Spring Security's form-login (used by the authorization-code flow). */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final LoginAttemptService attempts;

    public AppUserDetailsService(UserRepository users, LoginAttemptService attempts) {
        this.users = users;
        this.attempts = attempts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByEmail(username.trim().toLowerCase(Locale.ROOT))
                .filter(u -> u.getPasswordHash() != null)
                .map(u -> org.springframework.security.core.userdetails.User.withUsername(u.getEmail())
                        .password(u.getPasswordHash())
                        .roles(u.getRole().name())
                        .disabled(!u.isEnabled())
                        .accountLocked(attempts.isLocked(u.getEmail())) // checked before the password
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }
}
