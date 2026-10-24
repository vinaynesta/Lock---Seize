package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A single-use code for when the authenticator is lost (roadmap 3.1). Only its SHA-256 is stored. */
@Entity
@Table(name = "mfa_recovery_codes")
class MfaRecoveryCode {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String codeHash;

    private Instant usedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaRecoveryCode() {}

    MfaRecoveryCode(UUID userId, String codeHash, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = now;
    }

    void use(Instant now) {
        this.usedAt = now;
    }
}
