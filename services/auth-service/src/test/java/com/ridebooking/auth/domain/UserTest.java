package com.ridebooking.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class UserTest {

    private final PasswordHasher hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder(4));

    private User build(String email, String password, Role role) {
        return User.builder().email(email).password(password, hasher).role(role).build();
    }

    @Test
    void builderRequiresRole() {
        assertThatThrownBy(() -> User.builder().email("a@b.com").password("S3cret-pass!", hasher).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderNormalizesEmailAndNeverStoresRawPassword() {
        User user = build("  Rider@Example.COM ", "S3cret-pass!", Role.RIDER);

        assertThat(user.getEmail()).isEqualTo("rider@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("S3cret-pass!").startsWith("$2");
        assertThat(user.getRole()).isEqualTo(Role.RIDER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getId()).isNotNull();
    }

    @Test
    void matchesPasswordOnlyWhenCorrect() {
        User user = build("a@b.com", "S3cret-pass!", Role.DRIVER);

        assertThat(user.passwordMatches("S3cret-pass!", hasher)).isTrue();
        assertThat(user.passwordMatches("wrong", hasher)).isFalse();
    }

    @Test
    void rejectsWeakPasswordsAndBadEmails() {
        assertThatThrownBy(() -> build("a@b.com", "short", Role.RIDER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build("not-an-email", "S3cret-pass!", Role.RIDER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordAccountStartsUnverified() {
        assertThat(build("a@b.com", "S3cret-pass!", Role.RIDER).isEmailVerified()).isFalse();
    }

    @Test
    void socialOnlyUserHasNoPasswordAndIsVerified() {
        User user = User.builder().email("g@gmail.com").role(Role.RIDER).socialOnly().build();

        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.isEmailVerified()).isTrue(); // the identity provider already verified it
        assertThat(user.passwordMatches("anything-at-all", hasher)).isFalse();
    }

    @Test
    void clearPasswordDisablesPasswordLoginAndResetsLockout() {
        User user = build("a@b.com", "S3cret-pass!", Role.RIDER);
        user.recordFailedLogin();

        user.clearPassword();

        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.passwordMatches("S3cret-pass!", hasher)).isFalse();
        assertThat(user.getFailedLogins()).isZero();
    }

    @Test
    void markEmailVerifiedIsIdempotent() {
        User user = build("a@b.com", "S3cret-pass!", Role.RIDER);

        user.markEmailVerified();
        user.markEmailVerified();

        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void failedLoginsAccumulateAndResetOnSuccess() {
        User user = build("a@b.com", "S3cret-pass!", Role.RIDER);

        user.recordFailedLogin();
        user.recordFailedLogin();
        assertThat(user.getFailedLogins()).isEqualTo(2);

        user.recordSuccessfulLogin();
        assertThat(user.getFailedLogins()).isZero();
    }
}
