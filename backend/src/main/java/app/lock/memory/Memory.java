package app.lock.memory;

import app.lock.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The central domain object. Text content is stored as ciphertext; {@link MemoryService} owns
 * encryption. State transitions are guarded here and again by database triggers.
 */
@Entity
@Table(name = "memories")
public class Memory {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID vaultId;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "title_ct", nullable = false)
    private byte[] titleCt;

    @Column(name = "thought_ct")
    private byte[] thoughtCt;

    @Column(name = "feeling_ct")
    private byte[] feelingCt;

    @Column(name = "location_ct")
    private byte[] locationCt;

    @Column(nullable = false)
    private Instant occurredAt;

    private Instant revealAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryState state;

    private Instant sealingUntil;

    private Instant lockedAt;

    private String contentHash;

    @Column(nullable = false)
    private int keyVersion;

    @Column(updatable = false)
    private String idempotencyKey;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Memory() {}

    Memory(UUID id, UUID vaultId, UUID createdBy, int keyVersion, String idempotencyKey, Instant now) {
        this.id = id;
        this.vaultId = vaultId;
        this.createdBy = createdBy;
        this.keyVersion = keyVersion;
        this.idempotencyKey = idempotencyKey;
        this.state = MemoryState.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void writeContent(byte[] titleCt, byte[] thoughtCt, byte[] feelingCt, byte[] locationCt,
                      Instant occurredAt, Instant revealAt, Instant now) {
        requireState(MemoryState.DRAFT, "Only drafts can be edited");
        this.titleCt = titleCt;
        this.thoughtCt = thoughtCt;
        this.feelingCt = feelingCt;
        this.locationCt = locationCt;
        this.occurredAt = occurredAt;
        this.revealAt = revealAt;
        this.updatedAt = now;
    }

    void startSealing(String contentHash, Instant sealingUntil, Instant now) {
        requireState(MemoryState.DRAFT, "Only drafts can be locked");
        this.state = MemoryState.SEALING;
        this.contentHash = contentHash;
        this.sealingUntil = sealingUntil;
        this.updatedAt = now;
    }

    void cancelSealing(Instant now) {
        requireState(MemoryState.SEALING, "Memory is not in its cooling-off window");
        if (!now.isBefore(sealingUntil)) {
            throw ApiException.conflict("COOLING_OFF_ENDED", "The cooling-off window has ended");
        }
        this.state = MemoryState.DRAFT;
        this.contentHash = null;
        this.sealingUntil = null;
        this.updatedAt = now;
    }

    void completeLock(Instant now) {
        requireState(MemoryState.SEALING, "Memory is not sealing");
        this.state = MemoryState.LOCKED;
        this.lockedAt = now;
        this.updatedAt = now;
    }

    void archive(Instant now) {
        requireState(MemoryState.LOCKED, "Only locked memories can be archived");
        this.state = MemoryState.ARCHIVED;
        this.updatedAt = now;
    }

    void unarchive(Instant now) {
        requireState(MemoryState.ARCHIVED, "Memory is not archived");
        this.state = MemoryState.LOCKED;
        this.updatedAt = now;
    }

    /** Time capsule: a locked memory's content stays hidden until its reveal date. */
    public boolean isSealedCapsule(Instant now) {
        return state.isLocked() && revealAt != null && now.isBefore(revealAt);
    }

    private void requireState(MemoryState expected, String message) {
        if (state != expected) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION", message + " (current state: " + state + ")");
        }
    }

    public UUID getId() { return id; }
    public UUID getVaultId() { return vaultId; }
    public UUID getCreatedBy() { return createdBy; }
    public byte[] getTitleCt() { return titleCt; }
    public byte[] getThoughtCt() { return thoughtCt; }
    public byte[] getFeelingCt() { return feelingCt; }
    public byte[] getLocationCt() { return locationCt; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRevealAt() { return revealAt; }
    public MemoryState getState() { return state; }
    public Instant getSealingUntil() { return sealingUntil; }
    public Instant getLockedAt() { return lockedAt; }
    public String getContentHash() { return contentHash; }
    public int getKeyVersion() { return keyVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
