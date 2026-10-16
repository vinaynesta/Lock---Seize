package app.lock.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes audit events in the caller's transaction, so an audited change and its record commit together. */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEventType type, UUID actorId, UUID vaultId, UUID memoryId, Map<String, Object> metadata) {
        repository.save(new AuditEvent(vaultId, memoryId, actorId, type, metadata, Instant.now(clock)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEventType type, UUID actorId, UUID vaultId, UUID memoryId) {
        record(type, actorId, vaultId, memoryId, Map.of());
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> historyOf(UUID memoryId) {
        return repository.findByMemoryIdOrderByCreatedAtAsc(memoryId);
    }
}
