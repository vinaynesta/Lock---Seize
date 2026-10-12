package app.lock.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Metadata for one media object. The bytes live in object storage under {@link #objectKey}. */
@Entity
@Table(name = "memory_assets")
public class MemoryAsset {

    public enum Type { PHOTO, VIDEO, AUDIO }

    /** INITIATED → VERIFIED → ATTACHED, with VERIFICATION_FAILED and EXPIRED as failure exits. */
    public enum UploadStatus { INITIATED, VERIFIED, VERIFICATION_FAILED, ATTACHED, EXPIRED }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID memoryId;

    @Column(nullable = false, updatable = false)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type assetType;

    @Column(nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus uploadStatus;

    @Column(nullable = false)
    private String encryptionMetadata;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant verifiedAt;

    protected MemoryAsset() {}

    public MemoryAsset(UUID id, UUID memoryId, String objectKey, Type assetType, String mimeType, long sizeBytes,
                       String checksumSha256, String encryptionMetadata, Instant now) {
        this.id = id;
        this.memoryId = memoryId;
        this.objectKey = objectKey;
        this.assetType = assetType;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.encryptionMetadata = encryptionMetadata;
        this.uploadStatus = UploadStatus.INITIATED;
        this.createdAt = now;
    }

    public void markVerified(Instant now) {
        this.uploadStatus = UploadStatus.VERIFIED;
        this.verifiedAt = now;
    }

    public void markVerificationFailed() {
        this.uploadStatus = UploadStatus.VERIFICATION_FAILED;
    }

    public void markExpired() {
        this.uploadStatus = UploadStatus.EXPIRED;
    }

    void markAttached() {
        this.uploadStatus = UploadStatus.ATTACHED;
    }

    public boolean isUsable() {
        return uploadStatus == UploadStatus.VERIFIED || uploadStatus == UploadStatus.ATTACHED;
    }

    public UUID getId() { return id; }
    public UUID getMemoryId() { return memoryId; }
    public String getObjectKey() { return objectKey; }
    public Type getAssetType() { return assetType; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getChecksumSha256() { return checksumSha256; }
    public UploadStatus getUploadStatus() { return uploadStatus; }
    public String getEncryptionMetadata() { return encryptionMetadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
