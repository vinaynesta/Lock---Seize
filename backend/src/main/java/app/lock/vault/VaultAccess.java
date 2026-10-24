package app.lock.vault;

import app.lock.common.ApiException;
import app.lock.crypto.ContentCipher;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Result of a successful server-side authorization check against a vault. Carries every version of
 * the vault's wrapped data key: content is decrypted with the version it was written under and new
 * content is encrypted with {@link #keyVersion()}, the current one (roadmap 2.4).
 */
public record VaultAccess(UUID vaultId, UUID userId, VaultMember.Role role, Vault.Type vaultType,
                          int keyVersion, Map<Integer, ContentCipher.WrappedKey> keys) {

    public boolean isOwner() {
        return role == VaultMember.Role.OWNER;
    }

    /** The key new content is encrypted with. */
    public ContentCipher.WrappedKey currentKey() {
        return key(keyVersion);
    }

    public ContentCipher.WrappedKey key(int version) {
        ContentCipher.WrappedKey key = keys.get(version);
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "KEY_MISSING", "Vault key version " + version + " is missing");
        }
        return key;
    }
}
