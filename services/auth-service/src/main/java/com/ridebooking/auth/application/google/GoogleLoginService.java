package com.ridebooking.auth.application.google;

import com.ridebooking.auth.application.session.SessionService;
import com.ridebooking.auth.domain.Role;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserIdentity;
import com.ridebooking.auth.domain.UserIdentityRepository;
import com.ridebooking.auth.domain.UserRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maps a verified Google identity to one of our users: find by Google subject, else link by verified
 * email, else create. The Google subject (not the email) is the durable key, because emails can change.
 */
@Service
public class GoogleLoginService {

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final SessionService sessions;
    private final TransactionTemplate tx;

    public GoogleLoginService(UserRepository users, UserIdentityRepository identities,
                              SessionService sessions, TransactionTemplate tx) {
        this.users = users;
        this.identities = identities;
        this.sessions = sessions;
        this.tx = tx;
    }

    /**
     * Each attempt runs in its own transaction. If two first-time logins race, the loser's insert hits a
     * unique constraint (which poisons its transaction), so it retries once in a fresh transaction and
     * finds the winner's rows.
     */
    public User resolveUser(GoogleIdentity google, Role roleForNewUser) {
        try {
            return tx.execute(status -> resolveOnce(google, roleForNewUser));
        } catch (DataIntegrityViolationException lostRace) {
            return tx.execute(status -> resolveOnce(google, roleForNewUser));
        }
    }

    private User resolveOnce(GoogleIdentity google, Role roleForNewUser) {
        Optional<UserIdentity> known = identities.findByProviderAndSubject(UserIdentity.GOOGLE, google.subject());
        if (known.isPresent()) {
            return requireEnabled(users.findById(known.get().getUserId())
                    .orElseThrow(() -> new GoogleLoginRejectedException("Linked user no longer exists")));
        }

        Optional<User> sameEmail = users.findByEmail(google.email());
        if (sameEmail.isPresent()) {
            User existing = requireEnabled(sameEmail.get());
            if (!existing.isEmailVerified()) {
                // The address was claimed with a password nobody verified. Google just proved who really
                // owns it, so cut off whoever set that password, including logins they already have.
                existing.clearPassword();
                sessions.revokeAll(existing.getId());
                existing.markEmailVerified();
            }
            users.save(existing);
            identities.saveAndFlush(UserIdentity.link(existing.getId(), UserIdentity.GOOGLE,
                    google.subject(), google.email()));
            return existing;
        }

        if (roleForNewUser == null || roleForNewUser == Role.ADMIN) {
            throw new GoogleLoginRejectedException("Role not allowed for self-signup");
        }
        User created = users.saveAndFlush(User.builder()
                .email(google.email()).role(roleForNewUser).socialOnly().build());
        identities.saveAndFlush(UserIdentity.link(created.getId(), UserIdentity.GOOGLE,
                google.subject(), google.email()));
        return created;
    }

    private static User requireEnabled(User user) {
        if (!user.isEnabled()) {
            throw new GoogleLoginRejectedException("Account disabled");
        }
        return user;
    }
}
