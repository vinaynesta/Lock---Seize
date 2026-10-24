package app.lock.media;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.config.LockProperties;
import app.lock.media.image.ImageResizer;
import app.lock.media.scan.MalwareScanner;
import app.lock.media.storage.ObjectStorage;
import app.lock.media.transcode.MediaTranscoder;
import app.lock.memory.AssetDerivative;
import app.lock.memory.AssetDerivativeRepository;
import app.lock.memory.MemoryAsset;
import app.lock.memory.MemoryAssetRepository;
import app.lock.outbox.OutboxEvent;
import app.lock.outbox.OutboxEventHandler;
import app.lock.outbox.OutboxPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The media processing worker (roadmaps 2.1 and 2.2), in process behind the outbox so it can move to
 * a separate service or queue later without touching producers:
 * <ol>
 *   <li>{@code asset.verified} → malware scan. Clean: the asset becomes VERIFIED (usable). Infected:
 *       REJECTED, the bytes are quarantined, and the owner sees it on the memory.</li>
 *   <li>{@code asset.scanned_clean} → derivatives under {@code derived/}: JPEG thumbnails of photos
 *       (2.1; HEIC/HEIF/WebP through the transcoder, F7), and for video
 *       and audio a playback MP4, plus thumbnails from a video still (2.8). The original is never
 *       modified, so the integrity proof is unaffected.</li>
 * </ol>
 *
 * <p>Storage and scanner I/O happen outside any transaction; each state change commits in its own
 * short transaction, so a slow or failing scan never holds locks or poisons the outbox batch. A scan
 * that throws is retried by the outbox, and the sweep below picks up anything the outbox gave up on.
 * Both steps are idempotent (invariant 8).
 */
@Component
public class AssetProcessor implements OutboxEventHandler {

    static final String ASSET_VERIFIED = "asset.verified";
    static final String ASSET_SCANNED_CLEAN = "asset.scanned_clean";

    private static final Logger log = LoggerFactory.getLogger(AssetProcessor.class);
    private static final int SWEEP_BATCH = 50;

    private final MemoryAssetRepository assets;
    private final AssetDerivativeRepository derivatives;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final MediaTranscoder transcoder;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final LockProperties props;
    private final Clock clock;
    private final Counter rejected;
    private final Counter thumbnailFailures;
    private final Counter transcodeFailures;

    public AssetProcessor(MemoryAssetRepository assets, AssetDerivativeRepository derivatives, ObjectStorage storage,
                          MalwareScanner scanner, MediaTranscoder transcoder, AuditService audit, OutboxPublisher outbox,
                          PlatformTransactionManager transactions, MeterRegistry registry, LockProperties props, Clock clock) {
        this.assets = assets;
        this.derivatives = derivatives;
        this.storage = storage;
        this.scanner = scanner;
        this.transcoder = transcoder;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.props = props;
        this.clock = clock;
        this.rejected = Counter.builder("lock.uploads.rejected")
                .description("Uploads rejected by the malware scan").register(registry);
        this.thumbnailFailures = Counter.builder("lock.thumbnails.failed")
                .description("Photos whose thumbnails could not be generated (unsupported format or corrupt)").register(registry);
        this.transcodeFailures = Counter.builder("lock.transcodes.failed")
                .description("Videos or audio whose playback rendition could not be generated").register(registry);
        Gauge.builder("lock.scan.pending", assets::countPendingScan)
                .description("Uploads waiting for the malware scan").register(registry);
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.equals(ASSET_VERIFIED) || eventType.equals(ASSET_SCANNED_CLEAN);
    }

    @Override
    public void handle(OutboxEvent event) {
        UUID assetId = event.getAggregateId();
        if (event.getEventType().equals(ASSET_VERIFIED)) {
            scan(assetId);
        } else {
            generateDerivatives(assetId);
        }
    }

    // ---- 2.2 Malware scanning -------------------------------------------------------------------

    /** Scans one pending asset. Returns false if there was nothing to scan (already done, or removed). */
    public boolean scan(UUID assetId) {
        Optional<MemoryAsset> pending = assets.findById(assetId)
                .filter(a -> a.getUploadStatus() == MemoryAsset.UploadStatus.PENDING_SCAN);
        if (pending.isEmpty()) {
            return false;
        }
        MalwareScanner.Verdict verdict;
        try (InputStream bytes = storage.read(pending.get().getObjectKey())) {
            verdict = scanner.scan(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (verdict.infected()) {
            // Quarantine before recording the rejection: if this crashes, the retry finds it still pending.
            storage.quarantine(pending.get().getObjectKey());
        }
        return Boolean.TRUE.equals(tx.execute(status -> applyVerdict(assetId, verdict)));
    }

    private boolean applyVerdict(UUID assetId, MalwareScanner.Verdict verdict) {
        MemoryAsset asset = assets.findForUpdate(assetId).orElse(null);
        if (asset == null || asset.getUploadStatus() != MemoryAsset.UploadStatus.PENDING_SCAN) {
            return false; // removed from the draft meanwhile, or another worker got there first
        }
        Instant now = Instant.now(clock);
        if (verdict.infected()) {
            asset.markRejected(now);
            rejected.increment();
            // The signature name helps the owner and support; it is not content, so it may be audited.
            audit.record(AuditEventType.ASSET_REJECTED, null, null, asset.getMemoryId(),
                    Map.of("assetId", assetId.toString(), "signature", String.valueOf(verdict.signature())));
            outbox.publish("asset", assetId, "asset.rejected", Map.of("memoryId", asset.getMemoryId().toString()));
        } else {
            asset.markScannedClean(now);
            audit.record(AuditEventType.ASSET_SCANNED, null, null, asset.getMemoryId(), Map.of("assetId", assetId.toString()));
            outbox.publish("asset", assetId, ASSET_SCANNED_CLEAN,
                    Map.of("objectKey", asset.getObjectKey(), "type", asset.getAssetType().name()));
        }
        return true;
    }

    // ---- 2.1 Thumbnails, 2.8 renditions -------------------------------------------------------

    /**
     * Writes every missing derivative of a usable asset. Photos get THUMB and PREVIEW; with a
     * transcoder configured, videos get PLAYBACK plus THUMB/PREVIEW from a still, and audio gets
     * PLAYBACK. What can't be produced is skipped: clients fall back to the original.
     */
    public void generateDerivatives(UUID assetId) {
        MemoryAsset asset = assets.findById(assetId).filter(MemoryAsset::isUsable).orElse(null);
        if (asset == null) {
            return;
        }
        List<AssetDerivative.Variant> missing = wanted(asset).stream()
                .filter(v -> !derivatives.existsByAssetIdAndVariant(assetId, v))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lock-media");
            // The original goes to disk, not memory: videos can be 2 GB.
            Path original = dir.resolve("original");
            try (InputStream in = storage.read(asset.getObjectKey())) {
                Files.copy(in, original, StandardCopyOption.REPLACE_EXISTING);
            }
            if (missing.contains(AssetDerivative.Variant.PLAYBACK)) {
                playback(asset, original, dir.resolve("playback.mp4"));
            }
            List<AssetDerivative.Variant> stills = missing.stream().filter(AssetDerivative.Variant::isImage).toList();
            if (!stills.isEmpty()) {
                Optional<BufferedImage> image = decodeStill(asset, original, dir);
                if (image.isEmpty()) {
                    thumbnailFailures.increment();
                    log.info("No thumbnails for asset {}: {} could not be decoded", assetId, asset.getMimeType());
                } else {
                    for (AssetDerivative.Variant variant : stills) {
                        ImageResizer.Rendition r = ImageResizer.resize(image.get(), variant.maxSide());
                        save(asset, variant, new ByteArrayInputStream(r.jpeg()), r.jpeg().length, "image/jpeg", r.width(), r.height());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private List<AssetDerivative.Variant> wanted(MemoryAsset asset) {
        return switch (asset.getAssetType()) {
            case PHOTO -> asset.getSizeBytes() > props.storage().maxPhotoBytes() ? List.of()
                    : List.of(AssetDerivative.Variant.THUMB, AssetDerivative.Variant.PREVIEW);
            case VIDEO -> transcoder.isEnabled()
                    ? List.of(AssetDerivative.Variant.PLAYBACK, AssetDerivative.Variant.THUMB, AssetDerivative.Variant.PREVIEW)
                    : List.of();
            case AUDIO -> transcoder.isEnabled() ? List.of(AssetDerivative.Variant.PLAYBACK) : List.of();
        };
    }

    /** Photos: ImageIO (JPEG, PNG), then the transcoder for HEIC/HEIF/WebP. Videos: a still frame. */
    private Optional<BufferedImage> decodeStill(MemoryAsset asset, Path original, Path dir) throws IOException {
        if (asset.getAssetType() == MemoryAsset.Type.VIDEO) {
            Path still = dir.resolve("still.png");
            try {
                transcoder.videoStill(original, still);
            } catch (RuntimeException e) {
                log.info("No still for video {}: {}", asset.getId(), e.getMessage());
                return Optional.empty();
            }
            return ImageResizer.decode(Files.readAllBytes(still));
        }
        byte[] bytes = Files.readAllBytes(original);
        Optional<BufferedImage> image = ImageResizer.decode(bytes);
        if (image.isPresent()) {
            return image;
        }
        Optional<Path> converted = transcoder.decodeImage(original, dir.resolve("decoded.png"));
        if (converted.isEmpty()) {
            return Optional.empty();
        }
        // ffmpeg applies the HEIC rotation itself when it decodes, so the PNG is already upright.
        return ImageResizer.decode(Files.readAllBytes(converted.get()));
    }

    private void playback(MemoryAsset asset, Path original, Path out) throws IOException {
        try {
            if (asset.getAssetType() == MemoryAsset.Type.VIDEO) {
                transcoder.videoPlayback(original, out, AssetDerivative.Variant.PLAYBACK.maxSide());
            } else {
                transcoder.audioPlayback(original, out);
            }
        } catch (RuntimeException e) {
            // Corrupt or exotic input. The original still plays where the device supports it.
            transcodeFailures.increment();
            log.info("No playback rendition for asset {}: {}", asset.getId(), e.getMessage());
            return;
        }
        long size = Files.size(out);
        try (InputStream in = Files.newInputStream(out)) {
            save(asset, AssetDerivative.Variant.PLAYBACK, in, size, asset.getAssetType() == MemoryAsset.Type.VIDEO ? "video/mp4" : "audio/mp4", 0, 0);
        }
    }

    private void save(MemoryAsset asset, AssetDerivative.Variant variant, InputStream bytes, long size, String mime, int width, int height) {
        String key = AssetDerivative.keyFor(asset.getObjectKey(), variant);
        storage.write(key, bytes, size, mime);
        UUID assetId = asset.getId();
        tx.executeWithoutResult(status -> {
            // The asset may have been removed (and its bytes deleted) while we were working.
            if (assets.existsById(assetId) && !derivatives.existsByAssetIdAndVariant(assetId, variant)) {
                derivatives.save(new AssetDerivative(assetId, variant, key, mime, width, height, size, Instant.now(clock)));
            }
        });
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", dir, e.getMessage());
        }
    }

    // ---- Sweeps ---------------------------------------------------------------------------------

    /**
     * Safety net for the outbox, which stops retrying after a few attempts: if the scanner was down
     * for a while, pending assets are scanned here once it is back.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    void sweepPendingScans() {
        List<UUID> pending = tx.execute(status -> assets.lockPendingScan(SWEEP_BATCH).stream().map(MemoryAsset::getId).toList());
        int scanned = 0;
        for (UUID id : pending == null ? List.<UUID>of() : pending) {
            try {
                scanned += scan(id) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Scan of asset {} failed, will retry: {}", id, e.getMessage());
                return; // the scanner is probably still down; try the rest next time
            }
        }
        if (scanned > 0) {
            log.info("Scanned {} pending uploads", scanned);
        }
    }

    /**
     * Backfills derivatives for assets uploaded before 2.1/2.8, or before a transcoder was configured.
     * Only formats that can be processed now are picked up, so nothing is retried forever.
     */
    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT2M")
    void backfillDerivatives() {
        // Skip very recent uploads: the outbox is about to handle them.
        Instant before = Instant.now(clock).minus(Duration.ofMinutes(10));
        List<MemoryAsset> todo = transcoder.isEnabled()
                ? assets.findWithoutDerivativesIncludingMedia(before, SWEEP_BATCH)
                : assets.findPhotosWithoutDerivatives(before, SWEEP_BATCH);
        for (MemoryAsset a : todo) {
            try {
                generateDerivatives(a.getId());
            } catch (RuntimeException e) {
                log.warn("Thumbnail backfill for asset {} failed: {}", a.getId(), e.getMessage());
            }
        }
    }
}
