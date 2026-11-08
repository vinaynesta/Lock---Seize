package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decrypted (or, in a sealed vault, still encrypted) authorization-checked view of a memory. For a time capsule that has not been revealed
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
        Coordinates coordinates,
        Instant occurredAt,
        Instant revealAt,
        boolean sealedCapsule,
        Instant sealingUntil,
        Instant lockedAt,
        String contentHash,
        Instant createdAt,
        List<AssetView> assets,
        Encrypted encrypted) {

    /**
     * Sealed vault (roadmap 3.6): the content as stored, base64 AES-256-GCM under vault key
     * {@code keyVersion}, AAD {@code memoryId:field}. The plaintext fields above are then always null.
     */
    public record Encrypted(int keyVersion, String title, String thought, String feeling, String location, String coordinates) {}

    /** A memory's map position (roadmap 5.1), WGS 84 degrees. */
    public record Coordinates(double latitude, double longitude) {

        /** Stored form, encrypted like the other fields: "lat,lng" with 6 decimals (about 10 cm). */
        public String format() {
            return String.format(java.util.Locale.ROOT, "%.6f,%.6f", latitude, longitude);
        }

        public static Coordinates parse(String stored) {
            if (stored == null) {
                return null;
            }
            String[] parts = stored.split(",");
            return new Coordinates(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        }

        public boolean valid() {
            return Double.isFinite(latitude) && Double.isFinite(longitude)
                    && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        }
    }

    /**
     * {@code variants} lists the derivatives that exist (e.g. THUMB, PREVIEW); request one with
     * {@code GET /assets/{id}/download?variant=thumb}. Empty means only the original is available.
     */
    public record AssetView(UUID id, MemoryAsset.Type type, String mimeType, long sizeBytes,
                            String checksumSha256, MemoryAsset.UploadStatus uploadStatus, Instant createdAt,
                            List<AssetDerivative.Variant> variants) {

        public static AssetView of(MemoryAsset a) {
            return of(a, List.of());
        }

        public static AssetView of(MemoryAsset a, List<AssetDerivative.Variant> variants) {
            return new AssetView(a.getId(), a.getAssetType(), a.getMimeType(), a.getSizeBytes(),
                    a.getChecksumSha256(), a.getUploadStatus(), a.getCreatedAt(), variants);
        }
    }
}
