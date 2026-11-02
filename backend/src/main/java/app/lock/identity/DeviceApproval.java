package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A new device asking an unlocked one for the Sealed Vault secret key (roadmap 3.6e). The server
 * relays only a one-time public key and, once approved, the secret key sealed to it.
 */
@Entity
@Table(name = "device_approvals")
public class DeviceApproval {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private byte[] requestKey;

    private String deviceName;

    private byte[] sealedSecret;

    private Instant approvedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    protected DeviceApproval() {}

    DeviceApproval(UUID userId, byte[] requestKey, String deviceName, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.requestKey = requestKey;
        this.deviceName = deviceName;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    void approve(byte[] sealedSecret, Instant now) {
        this.sealedSecret = sealedSecret;
        this.approvedAt = now;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public byte[] getRequestKey() { return requestKey.clone(); }
    public String getDeviceName() { return deviceName; }
    public byte[] getSealedSecret() { return sealedSecret == null ? null : sealedSecret.clone(); }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
