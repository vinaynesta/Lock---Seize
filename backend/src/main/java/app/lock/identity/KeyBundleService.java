package app.lock.identity;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.crypto.E2eFormats;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sealed Vault key bundles (roadmap 3.6, piece 1). Creating a bundle and replacing its passphrase
 * wrap both need the account password and, when on, two-step verification: otherwise a stolen access
 * token could plant a key pair the attacker controls, and other devices would wrap vault keys to it.
 */
@Service
public class KeyBundleService {

    private final KeyBundleRepository bundles;
    private final IdentityService identity;
    private final MfaService mfa;
    private final AuditService audit;
    private final Clock clock;

    KeyBundleService(KeyBundleRepository bundles, IdentityService identity, MfaService mfa, AuditService audit, Clock clock) {
        this.bundles = bundles;
        this.identity = identity;
        this.mfa = mfa;
        this.audit = audit;
        this.clock = clock;
    }

    public record NewBundle(String publicKey, String wrappedByPassphrase, Map<String, Object> passphraseKdf,
                            String wrappedByRecovery, Map<String, Object> recoveryKdf) {}

    @Transactional
    public KeyBundle create(UUID userId, String password, String mfaCode, NewBundle req) {
        identity.verifyPassword(userId, password);
        mfa.require(userId, mfaCode);
        if (bundles.existsById(userId)) {
            throw ApiException.conflict("KEY_BUNDLE_EXISTS",
                    "You already have Sealed Vault keys. Recover them with your recovery phrase instead of creating new ones.");
        }
        KeyBundle bundle = bundles.save(new KeyBundle(userId,
                E2eFormats.publicKey(req.publicKey()),
                E2eFormats.wrappedSecretKey(req.wrappedByPassphrase(), "wrappedByPassphrase"),
                E2eFormats.kdf(req.passphraseKdf(), "passphraseKdf", false),
                E2eFormats.wrappedSecretKey(req.wrappedByRecovery(), "wrappedByRecovery"),
                E2eFormats.kdf(req.recoveryKdf(), "recoveryKdf", true),
                Instant.now(clock)));
        audit.record(AuditEventType.KEY_BUNDLE_CREATED, userId, null, null, Map.of("fingerprint", fingerprint(bundle.getPublicKey())));
        return bundle;
    }

    @Transactional(readOnly = true)
    public KeyBundle get(UUID userId) {
        return bundles.findById(userId).orElseThrow(() -> ApiException.notFound("Key bundle"));
    }

    @Transactional(readOnly = true)
    public Optional<KeyBundle> find(UUID userId) {
        return bundles.findById(userId);
    }

    /** Replaces the passphrase wrap. The recovery wrap and the key pair never change. */
    @Transactional
    public KeyBundle changePassphrase(UUID userId, String password, String mfaCode, String wrappedByPassphrase,
                                      Map<String, Object> passphraseKdf) {
        identity.verifyPassword(userId, password);
        mfa.require(userId, mfaCode);
        KeyBundle bundle = get(userId);
        bundle.rewrapPassphrase(E2eFormats.wrappedSecretKey(wrappedByPassphrase, "wrappedByPassphrase"),
                E2eFormats.kdf(passphraseKdf, "passphraseKdf", false), Instant.now(clock));
        audit.record(AuditEventType.KEY_BUNDLE_PASSPHRASE_CHANGED, userId, null, null);
        return bundle;
    }

    @Transactional(readOnly = true)
    public List<KeyBundle> forUsers(Collection<UUID> userIds) {
        return bundles.findByUserIdIn(userIds);
    }

    /** Account deletion: without the bundle, nothing the user's devices wrapped can be opened again. */
    @Transactional
    public void forget(UUID userId) {
        bundles.findById(userId).ifPresent(bundles::delete);
    }

    /** First 16 bytes of SHA-256(public key), in groups of four hex digits, for comparing keys out of band. */
    public static String fingerprint(byte[] publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            String hex = HexFormat.of().formatHex(digest, 0, 16);
            return String.join(" ", hex.split("(?<=\\G.{4})"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
