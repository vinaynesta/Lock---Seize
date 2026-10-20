package app.lock.vault;

import app.lock.crypto.ContentCipher;
import java.util.UUID;

/** Result of a successful server-side authorization check against a vault. */
public record VaultAccess(UUID vaultId, UUID userId, VaultMember.Role role, Vault.Type vaultType,
                          ContentCipher.WrappedKey wrappedKey, int keyVersion) {

    public boolean isOwner() {
        return role == VaultMember.Role.OWNER;
    }
}
