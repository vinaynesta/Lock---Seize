package app.lock.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One link in a refresh-token family. The raw token is only ever held by the client; this row keeps
 * its SHA-256 so a database leak does not hand out sessions.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false, updatable = false)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant rotatedAt;

    private Instant revokedAt;

    @Column(updatable = false)
    private String userAgent;

    protected RefreshToken() {}

    RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt, String userAgent) {
        this.userAgent = userAgent;
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    /** Already exchanged for a successor: presenting it again means someone kept a copy. */
    boolean isRotated() {
        return rotatedAt != null;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    void rotate(Instant now) {
        this.rotatedAt = now;
    }

    UUID getId() { return id; }
    Instant getCreatedAt() { return createdAt; }
    UUID getUserId() { return userId; }
    String getUserAgent() { return userAgent; }
    UUID getFamilyId() { return familyId; }
    Instant getExpiresAt() { return expiresAt; }
}
