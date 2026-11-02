package app.lock.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of a sealed vault's data key, wrapped by a member's device to one member's public key
 * (roadmap 3.6, piece 2). The server stores it and hands it back; it cannot unwrap it. Never updated.
 */
@Entity
@Table(name = "vault_member_keys")
public class VaultMemberKey {

    @Embeddable
    public record Id(UUID vaultId, UUID userId, int keyVersion) implements Serializable {}

    @EmbeddedId
    private Id id;

    @Column(nullable = false, updatable = false)
    private byte[] wrappedKey;

    /** The member whose device did the wrapping. */
    @Column(nullable = false, updatable = false)
    private UUID wrappedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected VaultMemberKey() {}

    VaultMemberKey(UUID vaultId, UUID userId, int keyVersion, byte[] wrappedKey, UUID wrappedBy, Instant now) {
        this.id = new Id(vaultId, userId, keyVersion);
        this.wrappedKey = wrappedKey;
        this.wrappedBy = wrappedBy;
        this.createdAt = now;
    }

    public UUID getVaultId() { return id.vaultId(); }
    public UUID getUserId() { return id.userId(); }
    public int getKeyVersion() { return id.keyVersion(); }
    public byte[] getWrappedKey() { return wrappedKey.clone(); }
    public UUID getWrappedBy() { return wrappedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
