package app.lock.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One queued notification, to a user or to every active member of a vault. See V9. */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum Status { PENDING, SENT, SKIPPED, CANCELLED, FAILED }

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    private UUID userId;

    private UUID vaultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationCategory category;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> data;

    @Column(nullable = false, updatable = false)
    private String dedupeKey;

    private String groupKey;

    @Column(nullable = false)
    private Instant sendAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private int attempts;

    private String lastError;

    private Instant sentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {}

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getVaultId() { return vaultId; }
    public NotificationCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Map<String, Object> getData() { return data; }
    public Instant getSendAt() { return sendAt; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }

    void markSent(Instant now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    /** Nobody to send to (no devices, or everyone opted out). */
    void markSkipped(Instant now) {
        this.status = Status.SKIPPED;
        this.sentAt = now;
    }

    void markFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        if (attempts >= maxAttempts) {
            this.status = Status.FAILED;
        }
    }
}
