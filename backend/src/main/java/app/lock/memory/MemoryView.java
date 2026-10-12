package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decrypted, authorization-checked view of a memory. For a time capsule that has not been revealed
 * yet, the content fields are null and {@code sealedCapsule} is true.
 */
public record MemoryView(
        UUID id,
        UUID vaultId,
        UUID createdBy,
        MemoryState state,
        String title,
        String thought,
        String feeling,
        String location,
        Instant occurredAt,
        Instant revealAt,
        boolean sealedCapsule,
        Instant sealingUntil,
        Instant lockedAt,
        String contentHash,
        Instant createdAt,
        List<AssetView> assets) {

    public record AssetView(UUID id, MemoryAsset.Type type, String mimeType, long sizeBytes,
                            String checksumSha256, MemoryAsset.UploadStatus uploadStatus, Instant createdAt) {

        public static AssetView of(MemoryAsset a) {
            return new AssetView(a.getId(), a.getAssetType(), a.getMimeType(), a.getSizeBytes(),
                    a.getChecksumSha256(), a.getUploadStatus(), a.getCreatedAt());
        }
    }
}
