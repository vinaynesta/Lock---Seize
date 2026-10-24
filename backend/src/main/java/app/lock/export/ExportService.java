package app.lock.export;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.config.LockProperties;
import app.lock.media.storage.ObjectStorage;
import app.lock.memory.MemoryAsset;
import app.lock.memory.MemoryAssetRepository;
import app.lock.memory.MemoryService;
import app.lock.memory.MemoryView;
import app.lock.memory.TimelineCursor;
import app.lock.outbox.OutboxEvent;
import app.lock.outbox.OutboxEventHandler;
import app.lock.outbox.OutboxPublisher;
import app.lock.vault.VaultService;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full ZIP export with original media (roadmap 2.3): "export anytime", including the photos.
 *
 * <p>{@code POST /vaults/{id}/exports} queues a job through the outbox. The worker builds the ZIP in a
 * temporary file, streaming page by page so neither the manifest nor the media is held in memory, then
 * stores it under {@code exports/}. The requester downloads it with a short-lived URL. The archive
 * holds decrypted content, so it expires after {@code lock.export.ttl} and only its requester can
 * fetch it. Exports see exactly what the requester can see: their own drafts, no unopened capsules.
 *
 * <p>ZIP layout: {@code manifest.json} (format {@code lock-export/v2}: memories, reflections, the
 * proof-of-preservation hash and each file's SHA-256) and {@code media/<memoryId>/<assetId>.<ext>}.
 */
@Service
public class ExportService implements OutboxEventHandler {

    static final String EXPORT_REQUESTED = "export.requested";
    static final String FORMAT = "lock-export/v2";

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final int PAGE_SIZE = 200;
    private static final Duration STUCK_AFTER = Duration.ofHours(6);

    private final VaultExportRepository exports;
    private final MemoryService memories;
    private final MemoryAssetRepository assets;
    private final VaultService vaults;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final LockProperties props;
    private final Clock clock;

    public ExportService(VaultExportRepository exports, MemoryService memories, MemoryAssetRepository assets,
                         VaultService vaults, ObjectStorage storage, AuditService audit, OutboxPublisher outbox,
                         JsonMapper json, PlatformTransactionManager transactions, LockProperties props, Clock clock) {
        this.exports = exports;
        this.memories = memories;
        this.assets = assets;
        this.vaults = vaults;
        this.storage = storage;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Reads for the manifest run in their own transaction, never the outbox dispatcher's: a domain
        // error (requester no longer a member) must fail this export, not roll back the whole batch.
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
        this.props = props;
        this.clock = clock;
    }

    // ---- API ------------------------------------------------------------------------------------

    /** Queues an export. Only one can be in progress per user per vault. */
    @Transactional
    public VaultExport request(UUID vaultId, UUID userId) {
        vaults.requireMember(vaultId, userId);
        if (exports.existsByVaultIdAndRequestedByAndStatus(vaultId, userId, VaultExport.Status.QUEUED)) {
            throw ApiException.conflict("EXPORT_IN_PROGRESS", "An export of this vault is already being prepared");
        }
        VaultExport export;
        try {
            export = exports.saveAndFlush(new VaultExport(vaultId, userId, Instant.now(clock)));
        } catch (DataIntegrityViolationException raced) { // ux_vault_exports_active
            throw ApiException.conflict("EXPORT_IN_PROGRESS", "An export of this vault is already being prepared");
        }
        audit.record(AuditEventType.EXPORT_REQUESTED, userId, vaultId, null, Map.of("exportId", export.getId().toString()));
        outbox.publish("export", export.getId(), EXPORT_REQUESTED, Map.of("vaultId", vaultId.toString()));
        return export;
    }

    @Transactional(readOnly = true)
    public List<VaultExport> list(UUID vaultId, UUID userId) {
        vaults.requireMember(vaultId, userId);
        return exports.findTop20ByVaultIdAndRequestedByOrderByRequestedAtDesc(vaultId, userId);
    }

    /** Only the requester can see an export; anyone else gets 404, as for vaults. */
    @Transactional(readOnly = true)
    public VaultExport get(UUID exportId, UUID userId) {
        return exports.findById(exportId)
                .filter(e -> e.getRequestedBy().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Export"));
    }

    @Transactional
    public URI downloadUrl(UUID exportId, UUID userId) {
        VaultExport export = get(exportId, userId);
        // Still a member? Someone removed from the vault must not walk away with its content.
        vaults.requireMember(export.getVaultId(), userId);
        if (export.getStatus() != VaultExport.Status.READY || !Instant.now(clock).isBefore(export.getExpiresAt())) {
            throw ApiException.conflict("EXPORT_NOT_READY", "Export is " + export.getStatus());
        }
        audit.record(AuditEventType.EXPORT_DOWNLOADED, userId, export.getVaultId(), null, Map.of("exportId", exportId.toString()));
        return storage.presignDownload(export.getObjectKey());
    }

    // ---- Worker ---------------------------------------------------------------------------------

    @Override
    public boolean supports(String eventType) {
        return EXPORT_REQUESTED.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        build(event.getAggregateId());
    }

    /**
     * Builds one queued export. Failures mark it FAILED (the user can request a new one) rather than
     * throwing, so a vault that cannot be exported does not keep the outbox retrying.
     */
    public void build(UUID exportId) {
        VaultExport export = exports.findById(exportId).filter(e -> e.getStatus() == VaultExport.Status.QUEUED).orElse(null);
        if (export == null) {
            return; // already built, or failed and retried by the user
        }
        String key = "exports/%s/%s.zip".formatted(export.getVaultId(), exportId);
        Path file = null;
        try {
            file = Files.createTempFile("lock-export-", ".zip");
            Counts counts = writeZip(export, file);
            long size = Files.size(file);
            try (InputStream in = Files.newInputStream(file)) {
                storage.write(key, in, size, "application/zip");
            }
            Instant now = Instant.now(clock);
            tx.executeWithoutResult(status -> exports.findForUpdate(exportId)
                    .filter(e -> e.getStatus() == VaultExport.Status.QUEUED)
                    .ifPresent(e -> {
                        e.markReady(key, size, counts.memories, counts.assets, now, now.plus(props.export().ttl()));
                        audit.record(AuditEventType.EXPORT_COMPLETED, e.getRequestedBy(), e.getVaultId(), null,
                                Map.of("exportId", exportId.toString(), "memories", counts.memories, "assets", counts.assets));
                        outbox.publish("export", exportId, "export.ready", Map.of("userId", e.getRequestedBy().toString()));
                    }));
        } catch (IOException | RuntimeException e) {
            log.warn("Export {} failed: {}", exportId, e.getMessage());
            fail(exportId, e instanceof ApiException api && api.status().value() == 404
                    ? "You are no longer a member of this vault" : "The export could not be built. Please try again.");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file); // decrypted content: never leave it on local disk
                } catch (IOException e) {
                    log.warn("Could not delete temporary export file {}", file);
                }
            }
        }
    }

    private void fail(UUID exportId, String reason) {
        tx.executeWithoutResult(status -> exports.findForUpdate(exportId)
                .filter(e -> e.getStatus() == VaultExport.Status.QUEUED)
                .ifPresent(e -> {
                    e.markFailed(reason, Instant.now(clock));
                    audit.record(AuditEventType.EXPORT_FAILED, e.getRequestedBy(), e.getVaultId(), null,
                            Map.of("exportId", exportId.toString()));
                }));
    }

    private record Counts(int memories, int assets) {}

    private record MediaFile(String path, String objectKey) {}

    public record ExportedAsset(UUID id, MemoryAsset.Type type, String mimeType, long sizeBytes,
                                String checksumSha256, String path) {}

    public record ExportedMemory(MemoryView memory, List<MemoryService.ReflectionView> reflections,
                                 List<ExportedAsset> files) {}

    /**
     * Pass 1 streams the manifest, one memory at a time, and notes which media to include. Pass 2
     * copies the media. Both stream, so memory use does not grow with the vault.
     */
    private Counts writeZip(VaultExport export, Path file) throws IOException {
        List<MediaFile> media = new ArrayList<>();
        int memoryCount;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            Integer written = readTx.execute(status -> {
                try {
                    return writeManifest(export, new NonClosing(zip), media);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            memoryCount = written == null ? 0 : written;
            zip.closeEntry();

            // Media copy happens outside any transaction: it can take a while for a large vault.
            for (MediaFile m : media) {
                zip.putNextEntry(new ZipEntry(m.path()));
                try (InputStream in = storage.read(m.objectKey())) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return new Counts(memoryCount, media.size());
    }

    /** Streams the manifest page by page and collects the media to copy. Returns the number of memories. */
    private int writeManifest(VaultExport export, OutputStream out, List<MediaFile> media) throws IOException {
        UUID userId = export.getRequestedBy();
        UUID vaultId = export.getVaultId();
        int memoryCount = 0;
        write(out, "{\"format\":\"" + FORMAT + "\",\"vaultId\":\"" + vaultId + "\",\"exportedAt\":\""
                + Instant.now(clock) + "\",\"memories\":[");
        TimelineCursor cursor = null;
        do {
            MemoryService.TimelinePage page = memories.timeline(vaultId, userId, null, null, null, cursor, PAGE_SIZE);
            for (MemoryView summary : page.memories()) {
                MemoryView full = memories.get(summary.id(), userId);
                List<MemoryService.ReflectionView> reflections = full.state().isLocked() && !full.sealedCapsule()
                        ? memories.listReflections(full.id(), userId)
                        : List.of();
                List<ExportedAsset> files = new ArrayList<>();
                for (MemoryView.AssetView a : full.assets()) { // empty for a sealed capsule
                    if (a.uploadStatus() == MemoryAsset.UploadStatus.VERIFIED || a.uploadStatus() == MemoryAsset.UploadStatus.ATTACHED) {
                        String path = "media/%s/%s%s".formatted(full.id(), a.id(), extension(a.mimeType()));
                        files.add(new ExportedAsset(a.id(), a.type(), a.mimeType(), a.sizeBytes(), a.checksumSha256(), path));
                        media.add(new MediaFile(path, assets.findById(a.id()).orElseThrow().getObjectKey()));
                    }
                }
                write(out, memoryCount++ == 0 ? "" : ",");
                out.write(json.writeValueAsBytes(new ExportedMemory(full, reflections, files)));
            }
            cursor = page.next();
        } while (cursor != null);
        write(out, "]}");
        return memoryCount;
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    static String extension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/heic" -> ".heic";
            case "image/heif" -> ".heif";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/quicktime" -> ".mov";
            case "video/webm", "audio/webm" -> ".webm";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4" -> ".m4a";
            case "audio/aac" -> ".aac";
            case "audio/ogg" -> ".ogg";
            case "audio/wav" -> ".wav";
            default -> "";
        };
    }

    /** Lets Jackson and helpers write into the open ZIP entry without closing the whole archive. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() {
            // the ZIP stream is closed by its owner
        }
    }

    // ---- Housekeeping ---------------------------------------------------------------------------

    /** Deletes archives past their expiry: they hold decrypted content. */
    @Transactional
    public int expireDue(int batchSize) {
        List<VaultExport> due = exports.lockExpired(Instant.now(clock), batchSize);
        for (VaultExport e : due) {
            storage.delete(e.getObjectKey());
            e.markExpired();
        }
        return due.size();
    }

    /** Exports stuck in QUEUED (outbox gave up, or the instance died mid-build) are failed so the user can retry. */
    @Transactional
    public int failStuck(int batchSize) {
        List<VaultExport> stuck = exports.lockStuck(Instant.now(clock).minus(STUCK_AFTER), batchSize);
        stuck.forEach(e -> e.markFailed("The export timed out. Please try again.", Instant.now(clock)));
        return stuck.size();
    }

    /** Account deletion (2.5): removes every archive the user requested, in any vault. */
    @Transactional
    public void deleteAllFor(UUID userId) {
        for (VaultExport e : exports.findByRequestedBy(userId)) {
            if (e.getObjectKey() != null) {
                storage.delete(e.getObjectKey());
            }
            exports.delete(e);
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void housekeeping() {
        int expired = expireDue(100);
        int stuck = failStuck(100);
        if (expired + stuck > 0) {
            log.info("Exports: {} expired, {} timed out", expired, stuck);
        }
    }
}
