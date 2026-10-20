package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    Optional<DeletionRequest> findByMemoryIdAndStatus(UUID memoryId, DeletionRequest.Status status);

    /** Due requests, row-locked with SKIP LOCKED so concurrent instances never purge the same memory twice. */
    @Query(value = """
            SELECT * FROM deletion_requests
            WHERE status = 'PENDING' AND execute_after <= :now
            ORDER BY execute_after
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DeletionRequest> lockDue(Instant now, int limit);
}
