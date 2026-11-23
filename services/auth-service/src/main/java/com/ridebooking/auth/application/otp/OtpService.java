package com.ridebooking.auth.application.otp;

import com.ridebooking.auth.domain.PasswordHasher;
import com.ridebooking.auth.domain.User;
import com.ridebooking.auth.domain.UserRepository;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * One-time-password login. Security properties:
 * <ul>
 *   <li>codes come from SecureRandom and are stored only as hashes, with a TTL;</li>
 *   <li>request() never reveals whether an email exists (same silent result);</li>
 *   <li>resend cooldown and a max-attempts limit bound brute force of the 6 digits;</li>
 *   <li>a code is single-use: only the caller whose delete succeeds is authenticated.</li>
 * </ul>
 */
@Service
public class OtpService {

    private final UserRepository users;
    private final OtpStore store;
    private final OtpSender sender;
    private final PasswordHasher hasher;
    private final OtpProperties props;
    private final SecureRandom random = new SecureRandom();

    public OtpService(UserRepository users, OtpStore store, OtpSender sender,
                      PasswordHasher hasher, OtpProperties props) {
        this.users = users;
        this.store = store;
        this.sender = sender;
        this.hasher = hasher;
        this.props = props;
    }

    public void request(String rawEmail) {
        String email = normalize(rawEmail);
        Optional<User> user = users.findByEmail(email).filter(User::isEnabled);
        if (user.isEmpty() || !store.tryStartCooldown(email, props.resendCooldown())) {
            return; // deliberately silent: no account enumeration, no resend flooding
        }
        String code = generateCode();
        store.put(email, hasher.hash(code), props.ttl());
        sender.send(email, code);
    }

    public boolean verify(String rawEmail, String code) {
        String email = normalize(rawEmail);
        Optional<String> hash = store.get(email);
        if (hash.isEmpty() || code == null) {
            return false;
        }
        if (store.incrementAttempts(email, props.ttl()) > props.maxAttempts()) {
            store.delete(email);
            return false;
        }
        return hasher.matches(code, hash.get()) && store.delete(email);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, props.length());
        return String.format("%0" + props.length() + "d", random.nextInt(bound));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
