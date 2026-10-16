package app.lock.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only record of a security-sensitive lifecycle action (invariant 9). */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    private UUID vaultId;

    private UUID memoryId;

    private UUID actorId;

    @Column(nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    AuditEvent(UUID vaultId, UUID memoryId, UUID actorId, AuditEventType type, Map<String, Object> metadata, Instant now) {
        this.id = UUID.randomUUID();
        this.vaultId = vaultId;
        this.memoryId = memoryId;
        this.actorId = actorId;
        this.eventType = type.name();
        this.metadata = metadata;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getVaultId() { return vaultId; }
    public UUID getMemoryId() { return memoryId; }
    public UUID getActorId() { return actorId; }
    public String getEventType() { return eventType; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
