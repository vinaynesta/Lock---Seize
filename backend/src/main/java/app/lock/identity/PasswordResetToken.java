package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A single-use password reset token (roadmap 2.6). Only its SHA-256 is stored. */
@Entity
@Table(name = "password_reset_tokens")
class PasswordResetToken {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant usedAt;

    protected PasswordResetToken() {}

    PasswordResetToken(UUID id, UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    void markUsed(Instant now) {
        this.usedAt = now;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    Instant getExpiresAt() { return expiresAt; }
}
