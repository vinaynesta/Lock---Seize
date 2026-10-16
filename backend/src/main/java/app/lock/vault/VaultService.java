package app.lock.vault;

import app.lock.audit.AuditEventType;
import app.lock.audit.AuditService;
import app.lock.common.ApiException;
import app.lock.crypto.ContentCipher;
import app.lock.identity.IdentityService;
import app.lock.identity.User;
import app.lock.identity.UserCreatedEvent;
import app.lock.outbox.OutboxPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VaultService {

    private final VaultRepository vaults;
    private final VaultMemberRepository members;
    private final IdentityService identity;
    private final ContentCipher cipher;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public VaultService(VaultRepository vaults, VaultMemberRepository members, IdentityService identity,
                        ContentCipher cipher, AuditService audit, OutboxPublisher outbox, Clock clock) {
        this.vaults = vaults;
        this.members = members;
        this.identity = identity;
        this.cipher = cipher;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Every account gets a personal vault, created in the same transaction as the user. */
    @EventListener
    public void onUserCreated(UserCreatedEvent event) {
        create(event.userId(), Vault.Type.PERSONAL, "My Vault");
    }

    @Transactional
    public Vault create(UUID ownerId, Vault.Type type, String name) {
        Instant now = Instant.now(clock);
        Vault vault = vaults.save(new Vault(type, name.trim(), ownerId, cipher.newWrappedVaultKey(), now));
        members.save(VaultMember.owner(vault.getId(), ownerId, now));
        audit.record(AuditEventType.VAULT_CREATED, ownerId, vault.getId(), null, Map.of("type", type.name()));
        return vault;
    }

    @Transactional(readOnly = true)
    public List<Vault> listForUser(UUID userId) {
        return vaults.findActiveForUser(userId);
    }

    @Transactional(readOnly = true)
    public Vault get(UUID vaultId, UUID userId) {
        requireMember(vaultId, userId);
        return vaults.findById(vaultId).orElseThrow(() -> ApiException.notFound("Vault"));
    }

    @Transactional
    public Vault rename(UUID vaultId, UUID userId, String name) {
        requireOwner(vaultId, userId);
        Vault vault = vaults.findById(vaultId).orElseThrow(() -> ApiException.notFound("Vault"));
        vault.rename(name.trim(), Instant.now(clock));
        return vault;
    }

    /**
     * Authorization is always checked against the database (invariants 1 and 7). Non-members get
     * 404 rather than 403 so vault ids cannot be probed.
     */
    @Transactional(readOnly = true)
    public VaultAccess requireMember(UUID vaultId, UUID userId) {
        VaultMember member = members.findById(new VaultMember.Id(vaultId, userId))
                .filter(VaultMember::isActive)
                .orElseThrow(() -> ApiException.notFound("Vault"));
        Vault vault = vaults.findById(vaultId).orElseThrow(() -> ApiException.notFound("Vault"));
        return new VaultAccess(vaultId, userId, member.getRole(), vault.getType(), vault.getWrappedKey(), vault.getKeyVersion());
    }

    @Transactional(readOnly = true)
    public VaultAccess requireOwner(UUID vaultId, UUID userId) {
        VaultAccess access = requireMember(vaultId, userId);
        if (!access.isOwner()) {
            throw ApiException.forbidden("Only the vault owner can do this");
        }
        return access;
    }

    // ---- Membership (shared vaults) ------------------------------------------------------------

    @Transactional
    public VaultMember invite(UUID vaultId, UUID ownerId, String inviteeEmail) {
        VaultAccess access = requireOwner(vaultId, ownerId);
        if (access.vaultType() != Vault.Type.SHARED) {
            throw ApiException.badRequest("PERSONAL_VAULT", "Personal vaults cannot have members");
        }
        User invitee = identity.getByEmail(inviteeEmail);
        Instant now = Instant.now(clock);
        VaultMember member = members.findById(new VaultMember.Id(vaultId, invitee.getId()))
                .map(existing -> {
                    if (existing.getStatus() == VaultMember.Status.ACTIVE || existing.getStatus() == VaultMember.Status.INVITED) {
                        throw ApiException.conflict("ALREADY_MEMBER", "User is already a member or invited");
                    }
                    existing.reinvite(ownerId, now);
                    return existing;
                })
                .orElseGet(() -> members.save(VaultMember.invited(vaultId, invitee.getId(), ownerId, now)));
        audit.record(AuditEventType.MEMBER_INVITED, ownerId, vaultId, null, Map.of("invitee", invitee.getId().toString()));
        outbox.publish("vault", vaultId, "vault.member.invited", Map.of("userId", invitee.getId().toString()));
        return member;
    }

    @Transactional(readOnly = true)
    public List<VaultMember> pendingInvitations(UUID userId) {
        return members.findByUserIdAndStatus(userId, VaultMember.Status.INVITED);
    }

    @Transactional
    public VaultMember respondToInvitation(UUID vaultId, UUID userId, boolean accept) {
        VaultMember member = members.findById(new VaultMember.Id(vaultId, userId))
                .filter(m -> m.getStatus() == VaultMember.Status.INVITED)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (accept) {
            member.accept(Instant.now(clock));
            audit.record(AuditEventType.MEMBER_JOINED, userId, vaultId, null);
            outbox.publish("vault", vaultId, "vault.member.joined", Map.of("userId", userId.toString()));
        } else {
            member.decline();
            audit.record(AuditEventType.MEMBER_DECLINED, userId, vaultId, null);
        }
        return member;
    }

    @Transactional(readOnly = true)
    public List<VaultMember> listMembers(UUID vaultId, UUID userId) {
        requireMember(vaultId, userId);
        return members.findByVaultId(vaultId);
    }

    /**
     * Removal revokes future access only. Memories the member contributed stay in the vault, and
     * anything they already viewed cannot be recalled (design doc §22).
     */
    @Transactional
    public void removeMember(UUID vaultId, UUID ownerId, UUID memberId) {
        requireOwner(vaultId, ownerId);
        if (ownerId.equals(memberId)) {
            throw ApiException.badRequest("OWNER_CANNOT_REMOVE_SELF", "Use leave instead");
        }
        VaultMember member = members.findById(new VaultMember.Id(vaultId, memberId))
                .filter(m -> m.getStatus() == VaultMember.Status.ACTIVE || m.getStatus() == VaultMember.Status.INVITED)
                .orElseThrow(() -> ApiException.notFound("Member"));
        member.remove(Instant.now(clock));
        audit.record(AuditEventType.MEMBER_REMOVED, ownerId, vaultId, null, Map.of("member", memberId.toString()));
        outbox.publish("vault", vaultId, "vault.member.removed", Map.of("userId", memberId.toString()));
    }

    /**
     * Breakup protocol: any member, including the owner, may leave a shared vault. Their
     * contributions stay and nobody can delete anyone else's. A vault with no active members
     * goes DORMANT instead of being destroyed.
     */
    @Transactional
    public void leave(UUID vaultId, UUID userId) {
        VaultAccess access = requireMember(vaultId, userId);
        if (access.vaultType() == Vault.Type.PERSONAL) {
            throw ApiException.badRequest("PERSONAL_VAULT", "You cannot leave your personal vault");
        }
        Instant now = Instant.now(clock);
        members.findById(new VaultMember.Id(vaultId, userId)).orElseThrow().leave(now);
        audit.record(AuditEventType.MEMBER_LEFT, userId, vaultId, null);
        if (members.countActive(vaultId) == 0) {
            vaults.findById(vaultId).orElseThrow().markDormant(now);
        }
    }
}
