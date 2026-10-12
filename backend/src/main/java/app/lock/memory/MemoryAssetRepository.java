package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryAssetRepository extends JpaRepository<MemoryAsset, UUID> {

    List<MemoryAsset> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);

    long countByMemoryId(UUID memoryId);

    List<MemoryAsset> findByUploadStatusAndCreatedAtBefore(MemoryAsset.UploadStatus status, Instant cutoff, Pageable page);
}
