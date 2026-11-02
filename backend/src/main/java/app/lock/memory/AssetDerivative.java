package app.lock.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A rendition of an asset: a smaller JPEG of a photo or of a video's first frame (roadmap 2.1), or a
 * playback-friendly MP4 of a video or audio file (roadmap 2.8). It can be regenerated from the
 * original at any time, so it is not part of the integrity proof, is never object-locked, and may be
 * written after its memory has locked.
 */
@Entity
@Table(name = "asset_derivatives")
public class AssetDerivative {

    /**
     * Longest side in pixels: THUMB for grids, PREVIEW for full-screen viewing on a phone (both JPEG),
     * PLAYBACK an H.264/AAC MP4 that every phone and browser can stream (video, or audio only).
     */
    public enum Variant {
        THUMB(256, "jpg"), PREVIEW(1024, "jpg"), PLAYBACK(1280, "mp4");

        private final int maxSide;
        private final String extension;

        Variant(int maxSide, String extension) {
            this.maxSide = maxSide;
            this.extension = extension;
        }

        public int maxSide() {
            return maxSide;
        }

        public boolean isImage() {
            return extension.equals("jpg");
        }
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Variant variant;

    @Column(nullable = false, updatable = false)
    private String objectKey;

    @Column(nullable = false)
    private String mimeType;

    private int width;

    private int height;

    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AssetDerivative() {}

    public AssetDerivative(UUID assetId, Variant variant, String objectKey, String mimeType, int width, int height,
                           long sizeBytes, Instant now) {
        this.id = UUID.randomUUID();
        this.assetId = assetId;
        this.variant = variant;
        this.objectKey = objectKey;
        this.mimeType = mimeType;
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.createdAt = now;
    }

    /**
     * Derivatives live under {@code derived/}, keyed by the original. The key is deterministic, so
     * cleanup can delete them from the original's key alone, without a database lookup.
     */
    public static String keyFor(String originalKey, Variant variant) {
        return "derived/" + originalKey + "/" + variant.name().toLowerCase() + "." + variant.extension;
    }

    public UUID getId() { return id; }
    public UUID getAssetId() { return assetId; }
    public Variant getVariant() { return variant; }
    public String getObjectKey() { return objectKey; }
    public String getMimeType() { return mimeType; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getSizeBytes() { return sizeBytes; }
}
