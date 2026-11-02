package app.lock.vault;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.crypto.E2eFormats;
import app.lock.identity.KeyBundle;
import app.lock.identity.KeyBundleService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sealed vault data keys (roadmap 3.6, piece 2). The server never sees a key; it enforces who may
 * store a wrapped copy for whom:
 * <ul>
 *   <li><b>A new version</b> (the first after creation, or after a member left or was removed, which
 *       bumps {@code vaults.key_version}) is created by the first member device that uploads a copy for
 *       <em>every</em> active member at once. Until then nobody can write new content.</li>
 *   <li><b>An existing version</b> can be wrapped for active members who lack it (someone who just
 *       joined) by any member who already holds it.</li>
 * </ul>
 * The server cannot check that a device wrapped the same key it holds; a malicious member could hand a
 * newcomer a wrong key, which would only lock that newcomer out. Accepted (sealed-vault-design §3).
 */
@Service
public class SealedVaultKeyService {

    private final VaultRepository vaults;
    private final VaultMemberRepository members;
    private final VaultMemberKeyRepository keys;
    private final VaultService vaultService;
    private final KeyBundleService bundles;
    private final AuditService audit;
    private final Clock clock;

    SealedVaultKeyService(VaultRepository vaults, VaultMemberRepository members, VaultMemberKeyRepository keys,
                          VaultService vaultService, KeyBundleService bundles, AuditService audit, Clock clock) {
        this.vaults = vaults;
        this.members = members;
        this.keys = keys;
        this.vaultService = vaultService;
        this.bundles = bundles;
        this.audit = audit;
        this.clock = clock;
    }

    /** What a member's device needs: its own wrapped copies, and who still lacks the current version. */
    public record KeyState(UUID vaultId, int currentVersion, List<VaultMemberKey> myKeys,
                           List<MemberPublicKey> members, Set<UUID> missingCurrent) {}

    /** An active member's public key, so a device can wrap a vault key to them. */
    public record MemberPublicKey(UUID userId, byte[] publicKey, String fingerprint) {}

    @Transactional(readOnly = true)
    public KeyState state(UUID vaultId, UUID userId) {
        VaultAccess access = requireSealed(vaultId, userId);
        List<UUID> active = activeMemberIds(vaultId);
        Set<UUID> haveCurrent = keys.findForVersion(vaultId, access.keyVersion()).stream()
                .map(VaultMemberKey::getUserId).collect(Collectors.toSet());
        Set<UUID> missing = new TreeSet<>(active);
        missing.removeAll(haveCurrent);
        List<MemberPublicKey> publicKeys = bundles.forUsers(active).stream()
                .map(b -> new MemberPublicKey(b.getUserId(), b.getPublicKey(), KeyBundleService.fingerprint(b.getPublicKey())))
                .toList();
        return new KeyState(vaultId, access.keyVersion(), keys.findForMember(vaultId, userId), publicKeys, missing);
    }

    /** Stores wrapped copies of key {@code version} for the given members (base64 blobs keyed by user id). */
    @Transactional
    public KeyState addKeys(UUID vaultId, UUID userId, int version, Map<UUID, String> wrapped) {
        VaultAccess access = requireSealed(vaultId, userId);
        Vault vault = vaults.findForUpdate(vaultId).orElseThrow(() -> ApiException.notFound("Vault"));
        if (wrapped == null || wrapped.isEmpty()) {
            throw ApiException.badRequest("INVALID_E2E_PAYLOAD", "keys must not be empty");
        }
        if (version < 1 || version > vault.getKeyVersion()) {
            throw ApiException.conflict("STALE_KEY_VERSION", "Key version " + version + " does not exist (current: " + vault.getKeyVersion() + ")");
        }
        Set<UUID> active = new HashSet<>(activeMemberIds(vaultId));
        Set<UUID> existing = keys.findForVersion(vaultId, version).stream().map(VaultMemberKey::getUserId).collect(Collectors.toSet());
        for (UUID recipient : wrapped.keySet()) {
            if (!active.contains(recipient)) {
                throw ApiException.badRequest("NOT_A_MEMBER", "Keys can only be wrapped for active members: " + recipient);
            }
            if (existing.contains(recipient)) {
                throw ApiException.conflict("KEY_EXISTS", "Member " + recipient + " already has key version " + version);
            }
        }
        if (existing.isEmpty()) {
            // A brand-new version: only the current one can be created, and it must reach everyone at once.
            if (version != vault.getKeyVersion()) {
                throw ApiException.conflict("STALE_KEY_VERSION", "Only the current key version can be created");
            }
            if (!wrapped.keySet().equals(active)) {
                Set<UUID> missing = new TreeSet<>(active);
                missing.removeAll(wrapped.keySet());
                throw ApiException.conflict("MEMBERS_MISSING", "A new key version must be wrapped for every active member; missing: " + missing);
            }
        } else if (!existing.contains(userId)) {
            throw ApiException.forbidden("Only a member who holds key version " + version + " can share it");
        }
        Map<UUID, byte[]> publicKeys = new HashMap<>();
        bundles.forUsers(wrapped.keySet()).forEach(b -> publicKeys.put(b.getUserId(), b.getPublicKey()));
        Instant now = Instant.now(clock);
        for (Map.Entry<UUID, String> e : wrapped.entrySet()) {
            if (!publicKeys.containsKey(e.getKey())) {
                throw ApiException.conflict("KEY_BUNDLE_REQUIRED", "Member " + e.getKey() + " has no Sealed Vault keys yet");
            }
            keys.save(new VaultMemberKey(vaultId, e.getKey(), version,
                    E2eFormats.wrappedVaultKey(e.getValue(), "keys." + e.getKey()), userId, now));
        }
        audit.record(AuditEventType.VAULT_MEMBER_KEYS_ADDED, userId, vaultId, null,
                Map.of("keyVersion", version, "recipients", wrapped.size(), "newVersion", existing.isEmpty()));
        return state(vaultId, userId);
    }

    /** Content can be written only once the writer holds the vault's current key version. */
    @Transactional(readOnly = true)
    public void requireCurrentKey(VaultAccess access, int keyVersion) {
        if (keyVersion != access.keyVersion()) {
            throw ApiException.conflict("STALE_KEY_VERSION",
                    "Encrypt with key version " + access.keyVersion() + ", not " + keyVersion + ". Reload the vault's keys.");
        }
        if (!keys.hasKey(access.vaultId(), access.userId(), keyVersion)) {
            throw ApiException.conflict("SEALED_KEY_PENDING",
                    "Your devices don't have this vault's current key yet. Open the vault on a device that does, or wait for a member to share it.");
        }
    }

    /** Joining a sealed vault needs a key bundle: there is nothing to wrap the vault key to otherwise. */
    @Transactional(readOnly = true)
    public void requireBundle(UUID userId) {
        if (bundles.find(userId).isEmpty()) {
            throw ApiException.conflict("KEY_BUNDLE_REQUIRED", "Set up Sealed Vault keys in Settings before joining this vault");
        }
    }

    /** Account deletion: the wrapped copies are useless without the bundle, but remove them anyway. */
    @Transactional
    public void forget(UUID userId) {
        keys.deleteAllForUser(userId);
    }

    private VaultAccess requireSealed(UUID vaultId, UUID userId) {
        VaultAccess access = vaultService.requireMember(vaultId, userId);
        if (!access.isSealed()) {
            throw ApiException.badRequest("NOT_A_SEALED_VAULT", "This vault is encrypted on the server; it has no member keys");
        }
        return access;
    }

    private List<UUID> activeMemberIds(UUID vaultId) {
        return members.findByVaultId(vaultId).stream().filter(VaultMember::isActive).map(VaultMember::getUserId).toList();
    }
}
