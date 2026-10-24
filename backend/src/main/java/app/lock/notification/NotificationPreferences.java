package app.lock.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Per-category opt-outs. Absent row: everything on. */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private boolean memoryLocked = true;

    @Column(nullable = false)
    private boolean capsuleRevealed = true;

    @Column(nullable = false)
    private boolean onThisDay = true;

    @Column(nullable = false)
    private boolean vaultInvitation = true;

    @Column(nullable = false)
    private boolean assetRejected = true;

    @Column(nullable = false)
    private boolean exportReady = true;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationPreferences() {}

    NotificationPreferences(UUID userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    public boolean allows(NotificationCategory category) {
        return switch (category) {
            case MEMORY_LOCKED -> memoryLocked;
            case CAPSULE_REVEALED -> capsuleRevealed;
            case ON_THIS_DAY -> onThisDay;
            case VAULT_INVITATION -> vaultInvitation;
            case ASSET_REJECTED -> assetRejected;
            case EXPORT_READY -> exportReady;
            case DELETION_REMINDER -> true;
        };
    }

    void set(NotificationCategory category, boolean enabled, Instant now) {
        switch (category) {
            case MEMORY_LOCKED -> memoryLocked = enabled;
            case CAPSULE_REVEALED -> capsuleRevealed = enabled;
            case ON_THIS_DAY -> onThisDay = enabled;
            case VAULT_INVITATION -> vaultInvitation = enabled;
            case ASSET_REJECTED -> assetRejected = enabled;
            case EXPORT_READY -> exportReady = enabled;
            case DELETION_REMINDER -> { } // not optional
        }
        this.updatedAt = now;
    }

    public UUID getUserId() { return userId; }
}
