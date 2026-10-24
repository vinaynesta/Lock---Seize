package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A user's TOTP enrolment (roadmap 3.1). Pending until confirmed with a first code. */
@Entity
@Table(name = "user_mfa")
class UserMfa {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private byte[] secretWrapped;

    private String kekId;

    private Instant enabledAt;

    private Long lastUsedStep;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected UserMfa() {}

    UserMfa(UUID userId, byte[] secretWrapped, String kekId, Instant now) {
        this.userId = userId;
        this.secretWrapped = secretWrapped;
        this.kekId = kekId;
        this.createdAt = now;
    }

    /** Starting over before confirming: a new secret replaces the unconfirmed one. */
    void replaceSecret(byte[] secretWrapped, String kekId, Instant now) {
        this.secretWrapped = secretWrapped;
        this.kekId = kekId;
        this.createdAt = now;
        this.lastUsedStep = null;
    }

    void enable(Instant now) {
        this.enabledAt = now;
    }

    void used(long step) {
        this.lastUsedStep = step;
    }

    boolean isEnabled() {
        return enabledAt != null;
    }

    UUID getUserId() { return userId; }
    byte[] getSecretWrapped() { return secretWrapped; }
    String getKekId() { return kekId; }
    Instant getEnabledAt() { return enabledAt; }
    Long getLastUsedStep() { return lastUsedStep; }
}
