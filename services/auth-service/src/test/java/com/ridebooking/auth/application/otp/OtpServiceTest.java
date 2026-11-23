package com.ridebooking.auth.application.otp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.PasswordHasher;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class OtpServiceTest {

    static class FakeStore implements OtpStore {
        final Map<String, String> codes = new HashMap<>();
        final Map<String, Long> attempts = new HashMap<>();
        final List<String> cooldowns = new ArrayList<>();

        public boolean tryStartCooldown(String e, Duration d) { return !cooldowns.contains(e) && cooldowns.add(e); }
        public void put(String e, String h, Duration t) { codes.put(e, h); attempts.remove(e); }
        public Optional<String> get(String e) { return Optional.ofNullable(codes.get(e)); }
        public long incrementAttempts(String e, Duration t) { return attempts.merge(e, 1L, Long::sum); }
        public boolean delete(String e) { attempts.remove(e); return codes.remove(e) != null; }
    }

    final FakeStore store = new FakeStore();
    final List<String> sent = new ArrayList<>();
    final PasswordHasher hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder(4));
    final UserRepository users = Mockito.mock(UserRepository.class);
    OtpService service;

    @BeforeEach
    void setUp() {
        User rider = User.builder().email("rider@example.com").password("S3cret-pass!", hasher).role(Role.RIDER).build();
        Mockito.when(users.findByEmail("rider@example.com")).thenReturn(Optional.of(rider));
        Mockito.when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        service = new OtpService(users, store, (email, code) -> sent.add(code), hasher,
                new OtpProperties(6, Duration.ofMinutes(5), 3, Duration.ofSeconds(60)));
    }

    @Test
    void sendsSixDigitCodeAndStoresOnlyItsHash() {
        service.request("  Rider@Example.com ");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).matches("\\d{6}");
        assertThat(store.codes.get("rider@example.com")).startsWith("$2").doesNotContain(sent.get(0));
    }

    @Test
    void unknownEmailIsSilentAndSendsNothing() {
        service.request("ghost@example.com");

        assertThat(sent).isEmpty();
        assertThat(store.codes).isEmpty();
    }

    @Test
    void resendWithinCooldownIsIgnored() {
        service.request("rider@example.com");
        service.request("rider@example.com");

        assertThat(sent).hasSize(1);
    }

    @Test
    void correctCodeVerifiesOnceOnly() {
        service.request("rider@example.com");
        String code = sent.get(0);

        assertThat(service.verify("rider@example.com", code)).isTrue();
        assertThat(service.verify("rider@example.com", code)).isFalse(); // single-use
    }

    @Test
    void wrongCodeFailsAndLocksAfterMaxAttempts() {
        service.request("rider@example.com");
        String right = sent.get(0);
        String wrong = "000000".equals(right) ? "111111" : "000000";

        assertThat(service.verify("rider@example.com", wrong)).isFalse();
        assertThat(service.verify("rider@example.com", wrong)).isFalse();
        assertThat(service.verify("rider@example.com", wrong)).isFalse();
        // 4th attempt exceeds the max of 3: even the right code no longer works
        assertThat(service.verify("rider@example.com", right)).isFalse();
    }

    @Test
    void noCodeRequestedMeansVerifyFails() {
        assertThat(service.verify("rider@example.com", "123456")).isFalse();
        assertThat(service.verify("rider@example.com", null)).isFalse();
    }
}
