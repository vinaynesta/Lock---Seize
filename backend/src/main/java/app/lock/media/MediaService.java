package app.lock.media;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.media.storage.ObjectStorage;
import app.lock.memory.AssetDerivative;
import app.lock.memory.AssetDerivativeRepository;
import app.lock.memory.Memory;
import app.lock.memory.MemoryAsset;
import app.lock.memory.MemoryAssetRepository;
import app.lock.memory.MemoryService;
import app.lock.outbox.OutboxPublisher;
import app.lock.quota.QuotaService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct-to-storage uploads (design doc §11). The API issues a presigned PUT; the client uploads
 * the bytes straight to object storage; the API then verifies the stored object's size and SHA-256
 * against what the client declared.
 */
@Service
public class MediaService {

    private static final Map<MemoryAsset.Type, Set<String>> ALLOWED_MIME = Map.of(
            MemoryAsset.Type.PHOTO, Set.of("image/jpeg", "image/png", "image/heic", "image/heif", "image/webp"),
            MemoryAsset.Type.VIDEO, Set.of("video/mp4", "video/quicktime", "video/webm"),
            MemoryAsset.Type.AUDIO, Set.of("audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/wav", "audio/webm"));

    private static final int MAX_ASSETS_PER_MEMORY = 50;

    private final MemoryService memories;
    private final QuotaService quotas;
    private final MemoryAssetRepository assets;
    private final AssetDerivativeRepository derivatives;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final LockProperties props;
    private final Clock clock;

    private final Counter uploadFailures;

    public MediaService(MemoryService memories, QuotaService quotas, MemoryAssetRepository assets, AssetDerivativeRepository derivatives,
                        ObjectStorage storage,
                        AuditService audit, OutboxPublisher outbox, MeterRegistry registry, LockProperties props, Clock clock) {
        this.uploadFailures = Counter.builder("lock.uploads.failed")
                .description("Uploads rejected on completion because size or checksum did not match").register(registry);
        this.memories = memories;
        this.quotas = quotas;
        this.assets = assets;
        this.derivatives = derivatives;
        this.storage = storage;
        this.audit = audit;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    public record UploadTicket(UUID assetId, URI uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}

    @Transactional
    public UploadTicket initiateUpload(UUID memoryId, UUID userId, MemoryAsset.Type type, String mimeType,
                                       long sizeBytes, String checksumSha256Base64) {
        Memory memory = memories.requireDraftOwnedByForUpdate(memoryId, userId);
        validate(type, mimeType, sizeBytes, checksumSha256Base64);
        quotas.requireRoomFor(userId, sizeBytes);
        if (assets.countByMemoryId(memoryId) >= MAX_ASSETS_PER_MEMORY) {
            throw ApiException.badRequest("TOO_MANY_ASSETS", "A memory can hold at most " + MAX_ASSETS_PER_MEMORY + " assets");
        }
        UUID assetId = UUID.randomUUID();
        // Opaque key: contains no user-provided names, so nothing about the content leaks through the path.
        String key = "vaults/%s/memories/%s/%s".formatted(memory.getVaultId(), memoryId, assetId);
        assets.save(new MemoryAsset(assetId, memoryId, key, type, mimeType, sizeBytes, checksumSha256Base64,
                "storage-sse:AES256", Instant.now(clock)));
        audit.record(AuditEventType.ASSET_UPLOAD_INITIATED, userId, memory.getVaultId(), memoryId,
                Map.of("assetId", assetId.toString(), "type", type.name(), "sizeBytes", sizeBytes));
        ObjectStorage.PresignedUpload presigned = storage.presignUpload(key, mimeType, sizeBytes, checksumSha256Base64);
        return new UploadTicket(assetId, presigned.url(), presigned.method(), presigned.headers(), presigned.expiresAt());
    }

    /** Idempotent: completing an already verified (or scanning) asset returns it unchanged. */
    @Transactional
    public MemoryAsset completeUpload(UUID assetId, UUID userId) {
        MemoryAsset asset = assets.findById(assetId).orElseThrow(() -> ApiException.notFound("Asset"));
        Memory memory = memories.requireDraftOwnedByForUpdate(asset.getMemoryId(), userId);
        if (asset.isUsable() || asset.getUploadStatus() == MemoryAsset.UploadStatus.PENDING_SCAN) {
            return asset;
        }
        if (asset.getUploadStatus() != MemoryAsset.UploadStatus.INITIATED) {
            throw ApiException.conflict("UPLOAD_NOT_PENDING", "Asset upload is " + asset.getUploadStatus());
        }
        var info = storage.head(asset.getObjectKey())
                .orElseThrow(() -> ApiException.conflict("UPLOAD_NOT_FOUND", "No object has been uploaded for this asset yet"));
        boolean sizeOk = info.sizeBytes() == asset.getSizeBytes();
        boolean checksumOk = info.checksumSha256Base64() == null || info.checksumSha256Base64().equals(asset.getChecksumSha256());
        if (!sizeOk || !checksumOk) {
            asset.markVerificationFailed();
            uploadFailures.increment();
            audit.record(AuditEventType.ASSET_VERIFICATION_FAILED, userId, memory.getVaultId(), memory.getId(),
                    Map.of("assetId", assetId.toString(), "sizeOk", sizeOk, "checksumOk", checksumOk));
            outbox.publish("asset", assetId, "asset.verification_failed", Map.of("objectKey", asset.getObjectKey()));
            return asset;
        }
        // Bytes match what was declared. They stay unusable until the malware scan passes (roadmap 2.2).
        asset.markPendingScan(Instant.now(clock));
        audit.record(AuditEventType.ASSET_VERIFIED, userId, memory.getVaultId(), memory.getId(), Map.of("assetId", assetId.toString()));
        outbox.publish("asset", assetId, AssetProcessor.ASSET_VERIFIED,
                Map.of("memoryId", memory.getId().toString(), "objectKey", asset.getObjectKey(), "type", asset.getAssetType().name()));
        return asset;
    }

    /** Assets can be removed from a draft. After locking, the database trigger forbids it. */
    @Transactional
    public void removeFromDraft(UUID assetId, UUID userId) {
        MemoryAsset asset = assets.findById(assetId).orElseThrow(() -> ApiException.notFound("Asset"));
        memories.requireDraftOwnedByForUpdate(asset.getMemoryId(), userId);
        assets.delete(asset);
        outbox.publish("asset", assetId, "asset.removed", Map.of("objectKey", asset.getObjectKey()));
    }

    public record Download(URI url, AssetDerivative.Variant variant) {}

    /**
     * Authorize first, then hand out a short-lived URL (design doc §39, object URL leakage). With a
     * {@code variant}, returns that derivative if it exists and the original otherwise; the response
     * says which one it is (null variant: the original).
     */
    @Transactional(readOnly = true)
    public Download downloadUrl(UUID assetId, UUID userId, AssetDerivative.Variant variant) {
        MemoryAsset asset = assets.findById(assetId).orElseThrow(() -> ApiException.notFound("Asset"));
        Memory memory = memories.load(asset.getMemoryId());
        memories.requireReadable(memory, userId);
        if (memory.isSealedCapsule(Instant.now(clock))) {
            throw ApiException.conflict("CAPSULE_SEALED", "This time capsule has not opened yet");
        }
        if (!asset.isUsable()) {
            throw ApiException.conflict("ASSET_NOT_READY", "Asset upload is " + asset.getUploadStatus());
        }
        if (variant != null) {
            var derivative = derivatives.findByAssetIdAndVariant(assetId, variant);
            if (derivative.isPresent()) {
                return new Download(storage.presignDownload(derivative.get().getObjectKey()), variant);
            }
        }
        return new Download(storage.presignDownload(asset.getObjectKey()), null);
    }

    /** Reconciliation (design doc §12): uploads never completed within the expiry window are expired. */
    @Transactional
    public int expireStaleUploads(int batchSize) {
        Instant cutoff = Instant.now(clock).minus(props.memory().uploadExpiry());
        List<MemoryAsset> stale = assets.lockStaleUploads(cutoff, batchSize);
        for (MemoryAsset asset : stale) {
            asset.markExpired();
            audit.record(AuditEventType.ASSET_EXPIRED, null, null, asset.getMemoryId(), Map.of("assetId", asset.getId().toString()));
            outbox.publish("asset", asset.getId(), "asset.expired", Map.of("objectKey", asset.getObjectKey()));
        }
        return stale.size();
    }

    private void validate(MemoryAsset.Type type, String mimeType, long sizeBytes, String checksum) {
        if (!ALLOWED_MIME.get(type).contains(mimeType)) {
            throw ApiException.badRequest("UNSUPPORTED_MEDIA_TYPE", mimeType + " is not allowed for " + type);
        }
        long max = switch (type) {
            case PHOTO -> props.storage().maxPhotoBytes();
            case VIDEO -> props.storage().maxVideoBytes();
            case AUDIO -> props.storage().maxAudioBytes();
        };
        if (sizeBytes <= 0 || sizeBytes > max) {
            throw ApiException.badRequest("INVALID_SIZE", type + " must be between 1 and " + max + " bytes");
        }
        try {
            if (Base64.getDecoder().decode(checksum).length != 32) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CHECKSUM", "checksumSha256 must be a base64-encoded SHA-256 digest");
        }
    }
}
