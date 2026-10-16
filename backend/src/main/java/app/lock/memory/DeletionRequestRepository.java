package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

    Optional<DeletionRequest> findByMemoryIdAndStatus(UUID memoryId, DeletionRequest.Status status);

    List<DeletionRequest> findByStatusAndExecuteAfterLessThanEqual(DeletionRequest.Status status, Instant now, Pageable page);
}
