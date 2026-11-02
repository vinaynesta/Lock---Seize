package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.outbox.OutboxPublisher;
import app.lock.security.LoginAttemptGuard;
import app.lock.security.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset by email (roadmap 2.6).
 *
 * <p>Requesting a reset always answers the same way, whether or not the email has an account, so it
 * cannot be used to find out who uses Lock. A token is single-use, expires after
 * {@code lock.email.reset-ttl} (30 minutes), and only the newest one works. Using it sets the new
 * password and signs the user out everywhere.
 *
 * <p>Memory content is not affected: vault keys are wrapped by the KEK, never by the password
 * (decisions §2). A Sealed Vault (roadmap 3.6) cannot be recovered this way; see decisions §18.
 *
 * <p>The raw token is {@code HMAC-SHA256(server secret, token id)}. The mail worker rebuilds it from
 * the id in the outbox event, so the token itself is never written anywhere but the email.
 */
@Service
public class PasswordResetService {

    static final String RESET_REQUESTED = "password.reset.requested";
    static final String RESET_COMPLETED = "password.reset.completed";

    /** Per email address: enough for "I didn't get it", too few to flood someone's inbox. */
    private static final int MAX_REQUESTS_PER_HOUR = 3;

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokens;
    private final RateLimiter limiter;
    private final LoginAttemptGuard attempts;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final LockProperties props;
    private final Clock clock;
    private final byte[] hmacKey;

    public PasswordResetService(PasswordResetTokenRepository tokens, UserRepository users, PasswordEncoder passwordEncoder,
                                RefreshTokenService refreshTokens, RateLimiter limiter, LoginAttemptGuard attempts,
                                AuditService audit, OutboxPublisher outbox, LockProperties props, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.attempts = attempts;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
        // Derived from the JWT secret with its own label, so the two uses never share a key.
        this.hmacKey = hmac(Base64.getDecoder().decode(props.jwt().secret()), "lock:password-reset-key:v1");
    }

    /** Starts a reset if the email belongs to an active account. Silent either way. */
    @Transactional
    public void request(String email) {
        String normalized = email.trim().toLowerCase();
        if (!limiter.tryAcquire("reset:" + normalized, MAX_REQUESTS_PER_HOUR, Duration.ofHours(1)).isZero()) {
            return; // same answer as success: throttling must not reveal anything either
        }
        Optional<User> user = users.findByEmailIgnoreCase(normalized).filter(u -> u.getStatus() == User.Status.ACTIVE);
        if (user.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        UUID userId = user.get().getId();
        tokens.invalidateAll(userId, now);
        UUID id = UUID.randomUUID();
        PasswordResetToken token = tokens.save(new PasswordResetToken(id, userId,
                RefreshTokenService.hash(rawToken(id)), now, now.plus(props.email().resetTtl())));
        audit.record(AuditEventType.PASSWORD_RESET_REQUESTED, userId, null, null);
        outbox.publish("user", userId, RESET_REQUESTED,
                Map.of("tokenId", id.toString(), "expiresAt", token.getExpiresAt().toString()));
    }

    /** Uses a reset token: sets the new password and revokes every session. */
    @Transactional
    public void confirm(String rawToken, String newPassword) {
        Instant now = Instant.now(clock);
        PasswordResetToken token = tokens.findForUpdate(RefreshTokenService.hash(rawToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(PasswordResetService::invalid);
        User user = users.findById(token.getUserId())
                .filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(PasswordResetService::invalid);
        user.changePasswordHash(passwordEncoder.encode(newPassword), now);
        tokens.invalidateAll(user.getId(), now);
        refreshTokens.revokeAll(user.getId());
        // Whoever was guessing the old password has nothing left to guess; let the owner straight in.
        attempts.recordSuccess("login:" + user.getEmail());
        attempts.recordSuccess("reauth:" + user.getId());
        audit.record(AuditEventType.PASSWORD_RESET, user.getId(), null, null);
        outbox.publish("user", user.getId(), RESET_COMPLETED, Map.of());
    }

    /** A password change (signed in) also kills any reset link still in the user's inbox. */
    @Transactional
    public void invalidateAll(UUID userId) {
        tokens.invalidateAll(userId, Instant.now(clock));
    }

    /** Rebuilds the token for the mail worker, if it can still be used. */
    @Transactional(readOnly = true)
    public Optional<String> usableToken(UUID tokenId) {
        Instant now = Instant.now(clock);
        return tokens.findById(tokenId).filter(t -> t.isUsable(now)).map(t -> rawToken(t.getId()));
    }

    @Transactional
    public int deleteExpired() {
        return tokens.deleteExpiredBefore(Instant.now(clock).minus(Duration.ofDays(1)));
    }

    String rawToken(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(hmacKey, id.toString()));
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "This reset link is invalid or has expired");
    }
}
