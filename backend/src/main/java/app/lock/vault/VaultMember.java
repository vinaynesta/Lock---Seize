package app.lock.vault;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_members")
public class VaultMember {

    public enum Role { OWNER, MEMBER }

    public enum Status { INVITED, ACTIVE, DECLINED, REMOVED, LEFT }

    @Embeddable
    public record Id(UUID vaultId, UUID userId) implements Serializable {}

    @EmbeddedId
    private Id id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private UUID invitedBy;

    private Instant invitedAt;

    private Instant joinedAt;

    private Instant removedAt;

    @Version
    private long version;

    protected VaultMember() {}

    static VaultMember owner(UUID vaultId, UUID userId, Instant now) {
        VaultMember m = new VaultMember();
        m.id = new Id(vaultId, userId);
        m.role = Role.OWNER;
        m.status = Status.ACTIVE;
        m.joinedAt = now;
        return m;
    }

    static VaultMember invited(UUID vaultId, UUID userId, UUID invitedBy, Instant now) {
        VaultMember m = new VaultMember();
        m.id = new Id(vaultId, userId);
        m.role = Role.MEMBER;
        m.status = Status.INVITED;
        m.invitedBy = invitedBy;
        m.invitedAt = now;
        return m;
    }

    void reinvite(UUID invitedBy, Instant now) {
        this.status = Status.INVITED;
        this.invitedBy = invitedBy;
        this.invitedAt = now;
        this.joinedAt = null;
        this.removedAt = null;
    }

    void accept(Instant now) {
        this.status = Status.ACTIVE;
        this.joinedAt = now;
    }

    void decline() {
        this.status = Status.DECLINED;
    }

    void remove(Instant now) {
        this.status = Status.REMOVED;
        this.removedAt = now;
    }

    void leave(Instant now) {
        this.status = Status.LEFT;
        this.removedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getVaultId() { return id.vaultId(); }
    public UUID getUserId() { return id.userId(); }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getInvitedAt() { return invitedAt; }
    public Instant getJoinedAt() { return joinedAt; }
}
