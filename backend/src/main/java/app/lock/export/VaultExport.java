package app.lock.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One asynchronous ZIP export of a vault (roadmap 2.3). QUEUED → READY → EXPIRED, or FAILED. */
@Entity
@Table(name = "vault_exports")
public class VaultExport {

    public enum Status { QUEUED, READY, FAILED, EXPIRED }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID vaultId;

    @Column(nullable = false, updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String objectKey;

    private Long sizeBytes;

    private Integer memoryCount;

    private Integer assetCount;

    private String error;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant completedAt;

    private Instant expiresAt;

    @Version
    private long version;

    protected VaultExport() {}

    VaultExport(UUID vaultId, UUID requestedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.vaultId = vaultId;
        this.requestedBy = requestedBy;
        this.status = Status.QUEUED;
        this.requestedAt = now;
    }

    void markReady(String objectKey, long sizeBytes, int memoryCount, int assetCount, Instant now, Instant expiresAt) {
        this.status = Status.READY;
        this.objectKey = objectKey;
        this.sizeBytes = sizeBytes;
        this.memoryCount = memoryCount;
        this.assetCount = assetCount;
        this.completedAt = now;
        this.expiresAt = expiresAt;
    }

    void markFailed(String error, Instant now) {
        this.status = Status.FAILED;
        this.error = error == null ? null : error.substring(0, Math.min(error.length(), 300));
        this.completedAt = now;
    }

    void markExpired() {
        this.status = Status.EXPIRED;
    }

    public UUID getId() { return id; }
    public UUID getVaultId() { return vaultId; }
    public UUID getRequestedBy() { return requestedBy; }
    public Status getStatus() { return status; }
    public String getObjectKey() { return objectKey; }
    public Long getSizeBytes() { return sizeBytes; }
    public Integer getMemoryCount() { return memoryCount; }
    public Integer getAssetCount() { return assetCount; }
    public String getError() { return error; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
