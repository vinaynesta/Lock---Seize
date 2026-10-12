package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IdentityService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                           AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public User register(String email, String password, String displayName) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = users.save(new User(normalized, passwordEncoder.encode(password), displayName.trim(), Instant.now(clock)));
        audit.record(AuditEventType.USER_REGISTERED, user.getId(), null, null);
        events.publishEvent(new UserCreatedEvent(user.getId(), user.getDisplayName()));
        return user;
    }

    @Transactional(readOnly = true)
    public TokenService.IssuedToken login(String email, String password) {
        User user = users.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));
        return tokens.issue(user);
    }

    /**
     * Password changes touch only the credential. Vault data keys are wrapped by the KEK, not by
     * the password, so no memory content is re-encrypted (invariant 4).
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = get(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Current password is incorrect");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now(clock));
        audit.record(AuditEventType.PASSWORD_CHANGED, userId, null, null);
    }

    /** Re-authentication for sensitive operations such as deletion requests. */
    @Transactional(readOnly = true)
    public void verifyPassword(UUID userId, String password) {
        if (!passwordEncoder.matches(password, get(userId).getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REAUTH_FAILED", "Password confirmation failed");
        }
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return users.findByEmailIgnoreCase(email.trim()).orElseThrow(() -> ApiException.notFound("User"));
    }
}
