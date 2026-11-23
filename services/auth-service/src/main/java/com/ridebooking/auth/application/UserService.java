package com.ridebooking.auth.application;

import com.ridebooking.auth.domain.DuplicateEmailException;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases for account management. Receives already-valid domain objects, never DTOs. */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /**
     * The existsByEmail check gives a fast, friendly failure. It is not sufficient alone:
     * two concurrent requests can both pass it. The UNIQUE constraint is the real guard,
     * so its violation is translated to the same domain error.
     */
    @Transactional
    public User register(User user) {
        if (users.existsByEmail(user.getEmail())) {
            throw new DuplicateEmailException(user.getEmail());
        }
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException(user.getEmail());
        }
    }
}
