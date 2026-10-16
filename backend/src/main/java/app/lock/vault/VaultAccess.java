package app.lock.vault;

import java.util.UUID;

/** Result of a successful server-side authorization check against a vault. */
public record VaultAccess(UUID vaultId, UUID userId, VaultMember.Role role, Vault.Type vaultType, byte[] wrappedKey, int keyVersion) {

    public boolean isOwner() {
        return role == VaultMember.Role.OWNER;
    }
}
