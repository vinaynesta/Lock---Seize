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
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IdentityService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                           RefreshTokenService refreshTokens, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
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

    @Transactional
    public Session login(String email, String password) {
        User user = users.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password"));
        return new Session(tokens.issue(user), refreshTokens.start(user.getId()));
    }

    /** Exchanges a refresh token for a new access token and a rotated refresh token. */
    @Transactional(noRollbackFor = ApiException.class) // a detected reuse must stay revoked
    public Session refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
        User user = users.findById(rotation.userId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));
        return new Session(tokens.issue(user), rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    public record Session(TokenService.IssuedToken accessToken, RefreshTokenService.IssuedRefreshToken refreshToken) {}

    /**
     * Password changes touch only the credential. Vault data keys are wrapped by the KEK, not by
     * the password, so no memory content is re-encrypted (invariant 4). Every existing session is
     * revoked; the caller gets a new one so the device that made the change stays signed in.
     */
    @Transactional
    public Session changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = get(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Current password is incorrect");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now(clock));
        refreshTokens.revokeAll(userId);
        audit.record(AuditEventType.PASSWORD_CHANGED, userId, null, null);
        return new Session(tokens.issue(user), refreshTokens.start(userId));
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
