package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.security.LoginAttemptGuard;
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
    private final PasswordResetService resets;
    private final MfaService mfa;
    private final MfaTickets mfaTickets;
    private final LoginAttemptGuard attempts;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IdentityService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                           RefreshTokenService refreshTokens, PasswordResetService resets, MfaService mfa, MfaTickets mfaTickets,
                           LoginAttemptGuard attempts, AuditService audit, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.resets = resets;
        this.mfa = mfa;
        this.mfaTickets = mfaTickets;
        this.attempts = attempts;
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
    public Session login(String email, String password, String userAgent) {
        String subject = "login:" + email.trim().toLowerCase();
        attempts.checkNotLocked(subject);
        User user = users.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            // Counted for unknown emails too, so the lockout does not reveal which accounts exist.
            attempts.recordFailure(subject);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        attempts.recordSuccess(subject);
        if (mfa.isEnabled(user.getId())) {
            // Right password, but no session until the second step (roadmap 3.1).
            return Session.mfaPending(mfaTickets.issue(user.getId()));
        }
        return new Session(tokens.issue(user), refreshTokens.start(user.getId(), userAgent));
    }

    /** Second step of a sign-in with two-step verification on: the ticket from login plus a code. */
    @Transactional(noRollbackFor = ApiException.class) // a failed code must still count towards the lockout
    public Session completeMfaLogin(String ticket, String code, String userAgent) {
        UUID userId = mfaTickets.verify(ticket)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "MFA_TICKET_INVALID", "Sign in again"));
        User user = users.findById(userId).filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "MFA_TICKET_INVALID", "Sign in again"));
        mfa.require(userId, code == null || code.isBlank() ? "-" : code);
        return new Session(tokens.issue(user), refreshTokens.start(userId, userAgent));
    }

    /** Exchanges a refresh token for a new access token and a rotated refresh token. */
    @Transactional(noRollbackFor = ApiException.class) // a detected reuse must stay revoked
    public Session refresh(String refreshToken, String userAgent) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken, userAgent);
        User user = users.findById(rotation.userId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));
        return new Session(tokens.issue(user), rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    /** Either a full session, or (MFA on, password right) only an {@code mfaTicket} for the second step. */
    public record Session(TokenService.IssuedToken accessToken, RefreshTokenService.IssuedRefreshToken refreshToken,
                          MfaTickets.Ticket mfaTicket) {

        Session(TokenService.IssuedToken accessToken, RefreshTokenService.IssuedRefreshToken refreshToken) {
            this(accessToken, refreshToken, null);
        }

        static Session mfaPending(MfaTickets.Ticket ticket) {
            return new Session(null, null, ticket);
        }

        public boolean mfaRequired() {
            return mfaTicket != null;
        }
    }

    /**
     * Password changes touch only the credential. Vault data keys are wrapped by the KEK, not by
     * the password, so no memory content is re-encrypted (invariant 4). Every existing session is
     * revoked; the caller gets a new one so the device that made the change stays signed in.
     */
    @Transactional
    public Session changePassword(UUID userId, String currentPassword, String newPassword, String userAgent) {
        String subject = "reauth:" + userId;
        attempts.checkNotLocked(subject);
        User user = get(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            attempts.recordFailure(subject);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Current password is incorrect");
        }
        attempts.recordSuccess(subject);
        user.changePasswordHash(passwordEncoder.encode(newPassword), Instant.now(clock));
        refreshTokens.revokeAll(userId);
        resets.invalidateAll(userId);
        audit.record(AuditEventType.PASSWORD_CHANGED, userId, null, null);
        return new Session(tokens.issue(user), refreshTokens.start(userId, userAgent));
    }

    /** Re-authentication for sensitive operations such as deletion requests. */
    @Transactional(readOnly = true)
    public void verifyPassword(UUID userId, String password) {
        // Shares the counter with change-password: a stolen access token must not allow guessing.
        String subject = "reauth:" + userId;
        attempts.checkNotLocked(subject);
        if (!passwordEncoder.matches(password, get(userId).getPasswordHash())) {
            attempts.recordFailure(subject);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "REAUTH_FAILED", "Password confirmation failed");
        }
        attempts.recordSuccess(subject);
    }

    /**
     * Account deletion (roadmap 2.5): signs the user out everywhere and anonymises the row. The
     * random password is hashed and discarded, so no password can ever sign in again.
     */
    @Transactional
    public void anonymize(UUID userId) {
        User user = get(userId);
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        user.anonymize(passwordEncoder.encode(java.util.HexFormat.of().formatHex(random)), Instant.now(clock));
        refreshTokens.revokeAll(userId);
        mfa.forget(userId);
    }

    @Transactional(readOnly = true)
    public User get(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return users.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Display names for a set of users; deleted accounts show as "Former member". */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> userIds) {
        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        users.findAllById(userIds).forEach(u -> names.put(u.getId(), u.getDisplayName()));
        return names;
    }
}
