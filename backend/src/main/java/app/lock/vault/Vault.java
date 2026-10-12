package app.lock.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vaults")
public class Vault {

    public enum Type { PERSONAL, SHARED }

    /** DORMANT: a shared vault with no active members left (breakup protocol); content is preserved. */
    public enum State { ACTIVE, DORMANT }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private State state;

    /** Vault data key wrapped by the KEK. */
    @Column(nullable = false)
    private byte[] wrappedKey;

    @Column(nullable = false)
    private int keyVersion;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Vault() {}

    Vault(Type type, String name, UUID ownerId, byte[] wrappedKey, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.name = name;
        this.ownerId = ownerId;
        this.state = State.ACTIVE;
        this.wrappedKey = wrappedKey;
        this.keyVersion = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    void markDormant(Instant now) {
        this.state = State.DORMANT;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public UUID getOwnerId() { return ownerId; }
    public State getState() { return state; }
    public byte[] getWrappedKey() { return wrappedKey; }
    public int getKeyVersion() { return keyVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
