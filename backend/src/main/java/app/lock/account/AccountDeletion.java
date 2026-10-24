package app.lock.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A scheduled account deletion (roadmap 2.5). PENDING until its grace period ends, cancellable until then. */
@Entity
@Table(name = "account_deletions")
public class AccountDeletion {

    public enum Status { PENDING, CANCELLED, COMPLETED }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

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

    protected AccountDeletion() {}

    AccountDeletion(UUID userId, Instant now, Instant executeAfter) {
        this.id = UUID.randomUUID();
        this.userId = userId;
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
    public UUID getUserId() { return userId; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExecuteAfter() { return executeAfter; }
}
