package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A user's end-to-end key pair (Sealed Vault, roadmap 3.6). Only the public key is usable by the
 * server; the secret key is stored wrapped by the Sealed Vault passphrase and by the recovery phrase,
 * neither of which the server ever sees. The public key never changes (DB trigger).
 */
@Entity
@Table(name = "user_key_bundles")
public class KeyBundle {

    @Id
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private byte[] publicKey;

    @Column(nullable = false)
    private byte[] wrappedByPassphrase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> passphraseKdf;

    @Column(nullable = false, updatable = false)
    private byte[] wrappedByRecovery;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, Object> recoveryKdf;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected KeyBundle() {}

    KeyBundle(UUID userId, byte[] publicKey, byte[] wrappedByPassphrase, Map<String, Object> passphraseKdf,
              byte[] wrappedByRecovery, Map<String, Object> recoveryKdf, Instant now) {
        this.userId = userId;
        this.publicKey = publicKey;
        this.wrappedByPassphrase = wrappedByPassphrase;
        this.passphraseKdf = passphraseKdf;
        this.wrappedByRecovery = wrappedByRecovery;
        this.recoveryKdf = recoveryKdf;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** New passphrase (changed, or set again after recovering with the phrase). Same secret key. */
    void rewrapPassphrase(byte[] wrapped, Map<String, Object> kdf, Instant now) {
        this.wrappedByPassphrase = wrapped;
        this.passphraseKdf = kdf;
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
    public byte[] getPublicKey() { return publicKey.clone(); }
    public byte[] getWrappedByPassphrase() { return wrappedByPassphrase.clone(); }
    public Map<String, Object> getPassphraseKdf() { return passphraseKdf; }
    public byte[] getWrappedByRecovery() { return wrappedByRecovery.clone(); }
    public Map<String, Object> getRecoveryKdf() { return recoveryKdf; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
