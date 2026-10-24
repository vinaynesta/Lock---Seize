package app.lock.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A device that can receive push notifications (design doc §23). */
@Entity
@Table(name = "devices")
public class Device {

    public enum Platform { IOS, ANDROID, WEB }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String pushToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    protected Device() {}

    Device(UUID userId, String pushToken, Platform platform, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.pushToken = pushToken;
        this.platform = platform;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** Same token seen again, possibly signed in as someone else now. */
    void refresh(UUID userId, Platform platform, Instant now) {
        this.userId = userId;
        this.platform = platform;
        this.lastSeenAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getPushToken() { return pushToken; }
    public Platform getPlatform() { return platform; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
