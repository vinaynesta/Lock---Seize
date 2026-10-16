package app.lock.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Deliberate, delayed deletion of a locked memory. "Protected from casual deletion" rather than
 * "impossible to delete" keeps the product compliant with erasure rights (GDPR Art. 17, CCPA, DPDP).
 */
@Entity
@Table(name = "deletion_requests")
public class DeletionRequest {

    public enum Status { PENDING, CANCELLED, COMPLETED }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID memoryId;

    @Column(nullable = false, updatable = false)
    private UUID requestedBy;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(nullable = false, updatable = false)
    private Instant executeAfter;

    private Instant closedAt;

    @Version
    private long version;

    protected DeletionRequest() {}

    DeletionRequest(UUID memoryId, UUID requestedBy, String reason, Instant now, Instant executeAfter) {
        this.id = UUID.randomUUID();
        this.memoryId = memoryId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.status = Status.PENDING;
        this.requestedAt = now;
        this.executeAfter = executeAfter;
    }

    void cancel(Instant now) {
        this.status = Status.CANCELLED;
        this.closedAt = now;
    }

    void complete(Instant now) {
        this.status = Status.COMPLETED;
        this.closedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMemoryId() { return memoryId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExecuteAfter() { return executeAfter; }
    public Instant getClosedAt() { return closedAt; }
}
