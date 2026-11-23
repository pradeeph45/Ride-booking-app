package com.ridebooking.auth.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ridebooking.auth.application.session.SessionService;
import com.ridebooking.auth.domain.BcryptPasswordHasher;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserIdentity;
import com.ridebooking.auth.domain.UserIdentityRepository;
import com.ridebooking.auth.domain.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class GoogleLoginServiceTest {

    static final GoogleIdentity GOOGLE_USER = new GoogleIdentity("google-sub-1", "person@gmail.com");

    final UserRepository users = Mockito.mock(UserRepository.class);
    final UserIdentityRepository identities = Mockito.mock(UserIdentityRepository.class);
    final SessionService sessions = Mockito.mock(SessionService.class);
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    GoogleLoginService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(Mockito.mock(PlatformTransactionManager.class));
        service = new GoogleLoginService(users, identities, sessions, tx);
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(users.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(identities.saveAndFlush(any(UserIdentity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User passwordUser(boolean verified) {
        User u = User.builder().email("person@gmail.com").password("S3cret-pass!",
                new BcryptPasswordHasher(encoder)).role(Role.RIDER).build();
        if (verified) u.markEmailVerified();
        return u;
    }

    @Test
    void firstGoogleLoginCreatesPasswordlessVerifiedUserAndLinksIdentity() {
        when(identities.findByProviderAndSubject("GOOGLE", "google-sub-1")).thenReturn(Optional.empty());
        when(users.findByEmail("person@gmail.com")).thenReturn(Optional.empty());

        User user = service.resolveUser(GOOGLE_USER, Role.DRIVER);

        assertThat(user.getEmail()).isEqualTo("person@gmail.com");
        assertThat(user.getRole()).isEqualTo(Role.DRIVER);
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.isEmailVerified()).isTrue();
        ArgumentCaptor<UserIdentity> linked = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identities).saveAndFlush(linked.capture());
        assertThat(linked.getValue().getSubject()).isEqualTo("google-sub-1");
        assertThat(linked.getValue().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void knownGoogleAccountLogsIntoTheSameUserWithoutCreatingAnything() {
        User existing = passwordUser(true);
        UserIdentity identity = UserIdentity.link(existing.getId(), "GOOGLE", "google-sub-1", "person@gmail.com");
        when(identities.findByProviderAndSubject("GOOGLE", "google-sub-1")).thenReturn(Optional.of(identity));
        when(users.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThat(service.resolveUser(GOOGLE_USER, Role.RIDER)).isSameAs(existing);
        verify(users, never()).saveAndFlush(any());
        verify(identities, never()).saveAndFlush(any());
    }

    @Test
    void verifiedAccountWithSameEmailIsLinkedAndKeepsItsPassword() {
        User existing = passwordUser(true);
        when(identities.findByProviderAndSubject(any(), any())).thenReturn(Optional.empty());
        when(users.findByEmail("person@gmail.com")).thenReturn(Optional.of(existing));

        User user = service.resolveUser(GOOGLE_USER, Role.RIDER);

        assertThat(user).isSameAs(existing);
        assertThat(user.getPasswordHash()).isNotNull();
        verify(sessions, never()).revokeAll(any());
        verify(identities).saveAndFlush(any(UserIdentity.class));
    }

    @Test
    void unverifiedPasswordAccountIsStrippedOfPasswordAndSessionsBeforeLinking() {
        // Someone registered person@gmail.com with a password nobody verified. Google proves the real
        // owner; the squatter's password and any login they already have must stop working.
        User squatted = passwordUser(false);
        when(identities.findByProviderAndSubject(any(), any())).thenReturn(Optional.empty());
        when(users.findByEmail("person@gmail.com")).thenReturn(Optional.of(squatted));

        User user = service.resolveUser(GOOGLE_USER, Role.RIDER);

        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.isEmailVerified()).isTrue();
        verify(sessions).revokeAll(squatted.getId());
    }

    @Test
    void disabledAccountCannotLogInWithGoogle() throws Exception {
        User existing = passwordUser(true);
        java.lang.reflect.Field enabled = User.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.set(existing, false);
        UserIdentity identity = UserIdentity.link(existing.getId(), "GOOGLE", "google-sub-1", "person@gmail.com");
        when(identities.findByProviderAndSubject(any(), any())).thenReturn(Optional.of(identity));
        when(users.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.resolveUser(GOOGLE_USER, Role.RIDER))
                .isInstanceOf(GoogleLoginRejectedException.class);
    }

    @Test
    void newUsersCannotChooseTheAdminRole() {
        when(identities.findByProviderAndSubject(any(), any())).thenReturn(Optional.empty());
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveUser(GOOGLE_USER, Role.ADMIN))
                .isInstanceOf(GoogleLoginRejectedException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void losingTheFirstLoginRaceRetriesAndReturnsTheWinnersUser() {
        // Two browser tabs sign in with Google at the same instant: both see "no user", one insert wins,
        // the other hits a unique-constraint error and must end up with the winner's account.
        User winner = passwordUser(true);
        UserIdentity identity = UserIdentity.link(winner.getId(), "GOOGLE", "google-sub-1", "person@gmail.com");
        when(identities.findByProviderAndSubject("GOOGLE", "google-sub-1"))
                .thenReturn(Optional.empty())            // first attempt: nothing yet
                .thenReturn(Optional.of(identity));      // retry: the winner has committed
        when(users.findByEmail("person@gmail.com")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));
        when(users.findById(winner.getId())).thenReturn(Optional.of(winner));

        assertThat(service.resolveUser(GOOGLE_USER, Role.RIDER)).isSameAs(winner);
    }
}
