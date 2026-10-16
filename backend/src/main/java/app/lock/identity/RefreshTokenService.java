package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Long-lived refresh tokens with rotation and reuse detection (roadmap 1.1).
 *
 * <p>Each login starts a family. Every refresh marks the presented token as rotated and issues its
 * successor. If a rotated or revoked token is ever presented again, someone holds a copy, so the
 * whole family is revoked and both the thief and the real user must sign in again.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final AuditService audit;
    private final LockProperties props;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, AuditService audit, LockProperties props, Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    /** Starts a new family, e.g. on login. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedRefreshToken start(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for its successor and returns the user it belongs to. Rejected
     * tokens all get the same 401, so the response does not reveal which check failed.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now(clock);
        RefreshToken current = tokens.findForUpdate(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);
        if (current.isRevoked() || current.isExpired(now)) {
            throw invalid();
        }
        if (current.isRotated()) {
            // Reuse of an old token: revoke the family. noRollbackFor keeps the revocation committed.
            tokens.revokeFamily(current.getFamilyId(), now);
            audit.record(AuditEventType.REFRESH_TOKEN_REUSED, current.getUserId(), null, null,
                    Map.of("familyId", current.getFamilyId().toString()));
            throw invalid();
        }
        current.rotate(now);
        return new Rotation(current.getUserId(), issue(current.getUserId(), current.getFamilyId()));
    }

    /** Logout: revokes the family the presented token belongs to. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        tokens.findForUpdate(hash(rawToken)).ifPresent(t -> {
            tokens.revokeFamily(t.getFamilyId(), Instant.now(clock));
            audit.record(AuditEventType.LOGGED_OUT, t.getUserId(), null, null);
        });
    }

    /** Signs the user out everywhere, e.g. after a password change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(UUID userId) {
        tokens.revokeAllForUser(userId, Instant.now(clock));
    }

    /** Housekeeping: drops tokens that expired more than a day ago. */
    @Transactional
    public int deleteExpired() {
        return tokens.deleteExpiredBefore(Instant.now(clock).minus(Duration.ofDays(1)));
    }

    private IssuedRefreshToken issue(UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now(clock);
        RefreshToken saved = tokens.save(new RefreshToken(userId, familyId, hash(raw), now, now.plus(props.jwt().refreshTtl())));
        return new IssuedRefreshToken(raw, saved.getExpiresAt());
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }

    public record IssuedRefreshToken(String value, Instant expiresAt) {}

    public record Rotation(UUID userId, IssuedRefreshToken refreshToken) {}
}
