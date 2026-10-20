package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemoryAssetRepository extends JpaRepository<MemoryAsset, UUID> {

    List<MemoryAsset> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);

    long countByMemoryId(UUID memoryId);

    /** Abandoned uploads, row-locked with SKIP LOCKED so concurrent instances expire each one once. */
    @Query(value = """
            SELECT * FROM memory_assets
            WHERE upload_status = 'INITIATED' AND created_at < :cutoff
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MemoryAsset> lockStaleUploads(Instant cutoff, int limit);
}
