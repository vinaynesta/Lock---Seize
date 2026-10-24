package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.crypto.KeyEncryptionService;
import app.lock.security.LoginAttemptGuard;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-step verification with TOTP (roadmap 3.1).
 *
 * <p>Enrolment: {@link #setUp} returns a secret (and otpauth:// URI for a QR code); {@link #confirm}
 * checks a first code, turns MFA on and returns ten single-use recovery codes, shown once.
 *
 * <p>Once on, a code (or a recovery code) is required to sign in, to request a deletion (of a memory
 * or the account), to export, and to turn MFA off. Wrong codes count towards the same lockout as
 * wrong passwords. Each TOTP step is accepted at most once, so an observed code cannot be replayed.
 */
@Service
public class MfaService {

    private static final int RECOVERY_CODES = 10;
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no 0/o, 1/l/i
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMfaRepository mfa;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final UserRepository users;
    private final KeyEncryptionService kek;
    private final LoginAttemptGuard attempts;
    private final AuditService audit;
    private final Clock clock;

    public MfaService(UserMfaRepository mfa, MfaRecoveryCodeRepository recoveryCodes, UserRepository users,
                      KeyEncryptionService kek, LoginAttemptGuard attempts, AuditService audit, Clock clock) {
        this.mfa = mfa;
        this.recoveryCodes = recoveryCodes;
        this.users = users;
        this.kek = kek;
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }

    public record Setup(String secret, String otpauthUri) {}

    public record Status(boolean enabled, Instant enabledAt, long recoveryCodesLeft) {}

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return mfa.findById(userId).filter(UserMfa::isEnabled).isPresent();
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        return mfa.findById(userId).filter(UserMfa::isEnabled)
                .map(m -> new Status(true, m.getEnabledAt(), recoveryCodes.countUnused(userId)))
                .orElse(new Status(false, null, 0));
    }

    /** Starts (or restarts) enrolment. Refused when MFA is already on: turn it off first. */
    @Transactional
    public Setup setUp(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = Instant.now(clock);
        byte[] secret = Totp.newSecret();
        byte[] wrapped = kek.wrap(secret);
        UserMfa existing = mfa.findForUpdate(userId).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-step verification is already on");
        }
        if (existing == null) {
            mfa.save(new UserMfa(userId, wrapped, kek.currentKekId(), now));
        } else {
            existing.replaceSecret(wrapped, kek.currentKekId(), now);
        }
        String base32 = Totp.base32(secret);
        String label = URLEncoder.encode("Lock:" + user.getEmail(), StandardCharsets.UTF_8).replace("+", "%20");
        return new Setup(base32, "otpauth://totp/" + label + "?secret=" + base32 + "&issuer=Lock&algorithm=SHA1&digits=6&period=30");
    }

    /** Confirms enrolment with a first code. Returns the recovery codes: the only time they are shown. */
    @Transactional(noRollbackFor = ApiException.class) // a wrong code is an answer, not a failure
    public List<String> confirm(UUID userId, String code) {
        UserMfa m = mfa.findForUpdate(userId)
                .orElseThrow(() -> ApiException.conflict("MFA_NOT_SET_UP", "Start two-step verification setup first"));
        if (m.isEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Two-step verification is already on");
        }
        OptionalLong step = Totp.verify(secretOf(m), code, Instant.now(clock), m.getLastUsedStep());
        if (step.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "That code didn't match. Check your authenticator app's clock.");
        }
        m.used(step.getAsLong());
        m.enable(Instant.now(clock));
        audit.record(AuditEventType.MFA_ENABLED, userId, null, null);
        return newRecoveryCodes(userId);
    }

    /** Replaces all recovery codes. Requires a current code. */
    @Transactional(noRollbackFor = ApiException.class) // a wrong code is an answer, not a failure
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        require(userId, code);
        return newRecoveryCodes(userId);
    }

    /** Turns MFA off. Requires a current code (or a recovery code). */
    @Transactional(noRollbackFor = ApiException.class) // a wrong code is an answer, not a failure
    public void disable(UUID userId, String code) {
        require(userId, code);
        mfa.deleteById(userId);
        recoveryCodes.deleteByUserId(userId);
        audit.record(AuditEventType.MFA_DISABLED, userId, null, null);
    }

    /**
     * For sensitive operations: passes when MFA is off, otherwise needs a valid TOTP or recovery code.
     * Failures share the re-authentication lockout, so codes cannot be brute-forced.
     */
    @Transactional(noRollbackFor = ApiException.class) // a wrong code is an answer, not a failure
    public void require(UUID userId, String code) {
        UserMfa m = mfa.findForUpdate(userId).filter(UserMfa::isEnabled).orElse(null);
        if (m == null) {
            return;
        }
        String subject = "mfa:" + userId;
        attempts.checkNotLocked(subject);
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "Enter the code from your authenticator app");
        }
        String normalized = code.replaceAll("[\\s-]", "").toLowerCase();
        OptionalLong step = Totp.verify(secretOf(m), normalized, Instant.now(clock), m.getLastUsedStep());
        if (step.isPresent()) {
            m.used(step.getAsLong());
            attempts.recordSuccess(subject);
            return;
        }
        var recovery = recoveryCodes.findUnusedForUpdate(userId, RefreshTokenService.hash(normalized));
        if (recovery.isPresent()) {
            recovery.get().use(Instant.now(clock));
            attempts.recordSuccess(subject);
            audit.record(AuditEventType.MFA_RECOVERY_CODE_USED, userId, null, null,
                    Map.of("remaining", recoveryCodes.countUnused(userId) - 1));
            return;
        }
        attempts.recordFailure(subject);
        throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", "That code didn't match");
    }

    /** Account deletion: nothing of the enrolment remains. */
    @Transactional
    public void forget(UUID userId) {
        mfa.findById(userId).ifPresent(mfa::delete);
        recoveryCodes.deleteByUserId(userId);
    }

    private List<String> newRecoveryCodes(UUID userId) {
        recoveryCodes.deleteByUserId(userId);
        Instant now = Instant.now(clock);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = randomCode();
            codes.add(code.substring(0, 5) + "-" + code.substring(5));
            recoveryCodes.save(new MfaRecoveryCode(userId, RefreshTokenService.hash(code), now));
        }
        return codes;
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private byte[] secretOf(UserMfa m) {
        return kek.unwrap(m.getSecretWrapped(), m.getKekId());
    }
}
