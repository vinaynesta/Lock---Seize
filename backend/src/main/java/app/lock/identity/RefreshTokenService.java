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

    private static final int MAX_USER_AGENT = 300;

    /** Starts a new family, e.g. on login. {@code userAgent} identifies the client, for the user's own reference. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedRefreshToken start(UUID userId, String userAgent) {
        return issue(userId, UUID.randomUUID(), userAgent);
    }

    /**
     * Exchanges a refresh token for its successor and returns the user it belongs to. Rejected
     * tokens all get the same 401, so the response does not reveal which check failed.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken, String userAgent) {
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
        // A rotation keeps the family's client unless a newer user agent was sent (e.g. after an app update).
        String agent = userAgent != null ? userAgent : current.getUserAgent();
        return new Rotation(current.getUserId(), issue(current.getUserId(), current.getFamilyId(), agent));
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

    /** Signed-in devices, for the user's own review (F9). {@code lastActiveAt} is the latest refresh. */
    public record SessionInfo(UUID id, String userAgent, Instant lastActiveAt, Instant expiresAt) {}

    @Transactional(readOnly = true)
    public java.util.List<SessionInfo> sessions(UUID userId) {
        return tokens.findActiveSessions(userId, Instant.now(clock)).stream()
                .map(t -> new SessionInfo(t.getFamilyId(), t.getUserAgent(), t.getCreatedAt(), t.getExpiresAt()))
                .sorted(java.util.Comparator.comparing(SessionInfo::lastActiveAt).reversed())
                .toList();
    }

    /** Housekeeping: drops tokens that expired more than a day ago. */
    @Transactional
    public int deleteExpired() {
        return tokens.deleteExpiredBefore(Instant.now(clock).minus(Duration.ofDays(1)));
    }

    private IssuedRefreshToken issue(UUID userId, UUID familyId, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now(clock);
        RefreshToken saved = tokens.save(new RefreshToken(userId, familyId, hash(raw), now, now.plus(props.jwt().refreshTtl()),
                truncate(userAgent)));
        return new IssuedRefreshToken(raw, saved.getExpiresAt());
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String clean = userAgent.replaceAll("\\p{Cntrl}", "").strip();
        return clean.length() > MAX_USER_AGENT ? clean.substring(0, MAX_USER_AGENT) : clean;
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
