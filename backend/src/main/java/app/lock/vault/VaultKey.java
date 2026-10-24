package app.lock.vault;

import app.lock.crypto.ContentCipher;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One version of a vault's data key, wrapped by the KEK (roadmap 2.4). New content uses the vault's
 * current version; existing content keeps the version it was encrypted with.
 */
@Entity
@Table(name = "vault_keys")
public class VaultKey {

    @Embeddable
    public record Id(UUID vaultId, int version) implements Serializable {}

    @EmbeddedId
    private Id id;

    @Column(nullable = false)
    private byte[] wrappedKey;

    /** Which KEK wrapped {@link #wrappedKey}; null for keys wrapped before this was recorded. */
    private String kekId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected VaultKey() {}

    VaultKey(UUID vaultId, int version, ContentCipher.WrappedKey key, Instant now) {
        this.id = new Id(vaultId, version);
        this.wrappedKey = key.bytes();
        this.kekId = key.kekId();
        this.createdAt = now;
    }

    /** KEK rotation: same data key, wrapped by a different KEK. */
    void rewrap(ContentCipher.WrappedKey key) {
        this.wrappedKey = key.bytes();
        this.kekId = key.kekId();
    }

    public UUID getVaultId() { return id.vaultId(); }
    public int getVersion() { return id.version(); }
    public ContentCipher.WrappedKey getWrappedKey() { return new ContentCipher.WrappedKey(wrappedKey, kekId); }
}
