package app.lock.memory;

import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MemoryAssetRepository extends JpaRepository<MemoryAsset, UUID> {

    List<MemoryAsset> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);

    long countByMemoryId(UUID memoryId);

    /** Row lock so the scan worker and the sweep never scan (and audit) one asset twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MemoryAsset a where a.id = :id")
    Optional<MemoryAsset> findForUpdate(UUID id);

    /** Abandoned uploads, row-locked with SKIP LOCKED so concurrent instances expire each one once. */
    @Query(value = """
            SELECT * FROM memory_assets
            WHERE upload_status = 'INITIATED' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MemoryAsset> lockStaleUploads(Instant cutoff, int limit);

    /** Assets waiting for the malware scan, oldest first, claimed with SKIP LOCKED (roadmap 2.2). */
    @Query(value = """
            SELECT * FROM memory_assets
            WHERE upload_status = 'PENDING_SCAN'
            ORDER BY verified_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MemoryAsset> lockPendingScan(int limit);

    /**
     * Bytes counted against a user's quota (roadmap 3.2): every asset in memories they created that is
     * stored or about to be. Failed, rejected and expired uploads do not count.
     */
    @Query(value = """
            SELECT coalesce(sum(a.size_bytes), 0) FROM memory_assets a JOIN memories m ON m.id = a.memory_id
            WHERE m.created_by = :userId
              AND a.upload_status IN ('INITIATED', 'PENDING_SCAN', 'VERIFIED', 'ATTACHED')
            """, nativeQuery = true)
    long bytesUsedBy(UUID userId);

    /**
     * Originals still hot whose memory has been locked for longer than the cold-tier age (roadmap 3.3),
     * claimed with SKIP LOCKED. Sealed time capsules wait until they have opened and been seen.
     */
    @Query(value = """
            SELECT a.* FROM memory_assets a JOIN memories m ON m.id = a.memory_id
            WHERE a.archived_at IS NULL AND a.upload_status = 'ATTACHED'
              AND m.state IN ('LOCKED', 'ARCHIVED') AND m.locked_at < :lockedBefore
              AND (m.reveal_at IS NULL OR m.reveal_at < :lockedBefore)
            ORDER BY m.locked_at
            LIMIT :limit
            FOR UPDATE OF a SKIP LOCKED
            """, nativeQuery = true)
    List<MemoryAsset> lockColdCandidates(Instant lockedBefore, int limit);

    @Query(value = "SELECT count(*) FROM memory_assets WHERE upload_status = 'PENDING_SCAN'", nativeQuery = true)
    long countPendingScan();

    /** Usable photos with no thumbnail yet: uploads from before 2.1, or ones whose generation failed. */
    @Query(value = """
            SELECT a.* FROM memory_assets a
            WHERE a.asset_type = 'PHOTO' AND a.upload_status IN ('VERIFIED', 'ATTACHED')
              AND a.mime_type IN ('image/jpeg', 'image/png') -- formats the resizer reads; others would retry forever
              AND NOT EXISTS (SELECT 1 FROM asset_derivatives d WHERE d.asset_id = a.id)
              AND a.created_at < :before
            ORDER BY a.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<MemoryAsset> findPhotosWithoutDerivatives(Instant before, int limit);

    /**
     * With a transcoder (roadmap 2.8): also videos and audio without a playback rendition, and photos
     * in formats only the transcoder decodes. Excludes assets already tried (any derivative exists).
     */
    @Query(value = """
            SELECT a.* FROM memory_assets a
            WHERE a.upload_status IN ('VERIFIED', 'ATTACHED')
              AND NOT EXISTS (SELECT 1 FROM asset_derivatives d WHERE d.asset_id = a.id)
              AND a.created_at < :before
            ORDER BY a.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<MemoryAsset> findWithoutDerivativesIncludingMedia(Instant before, int limit);
}
