package app.lock.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A dated, append-only note added to a locked memory ("Reading this 5 years later…"). */
@Entity
@Table(name = "memory_reflections")
public class Reflection {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID memoryId;

    @Column(nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "body_ct", nullable = false, updatable = false)
    private byte[] bodyCt;

    @Version
    private long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Reflection() {}

    Reflection(UUID id, UUID memoryId, UUID authorId, byte[] bodyCt, Instant now) {
        this.id = id;
        this.memoryId = memoryId;
        this.authorId = authorId;
        this.bodyCt = bodyCt;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMemoryId() { return memoryId; }
    public UUID getAuthorId() { return authorId; }
    public byte[] getBodyCt() { return bodyCt; }
    public Instant getCreatedAt() { return createdAt; }
}
