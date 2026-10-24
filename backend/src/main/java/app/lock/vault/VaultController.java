package app.lock.vault;

import app.lock.common.CurrentUser;
import app.lock.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VaultController {

    private final VaultService vaults;
    private final IdentityService identity;

    public VaultController(VaultService vaults, IdentityService identity) {
        this.vaults = vaults;
        this.identity = identity;
    }

    @PostMapping("/vaults")
    @ResponseStatus(HttpStatus.CREATED)
    public VaultResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateVaultRequest req) {
        return VaultResponse.of(vaults.create(CurrentUser.id(jwt), req.type(), req.name()));
    }

    @GetMapping("/vaults")
    public List<VaultResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return vaults.listForUser(CurrentUser.id(jwt)).stream().map(VaultResponse::of).toList();
    }

    @GetMapping("/vaults/{vaultId}")
    public VaultResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        return VaultResponse.of(vaults.get(vaultId, CurrentUser.id(jwt)));
    }

    @PatchMapping("/vaults/{vaultId}")
    public VaultResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                                @Valid @RequestBody RenameVaultRequest req) {
        return VaultResponse.of(vaults.rename(vaultId, CurrentUser.id(jwt), req.name()));
    }

    @GetMapping("/vaults/{vaultId}/members")
    public List<MemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        List<VaultMember> members = vaults.listMembers(vaultId, CurrentUser.id(jwt));
        Map<UUID, String> names = identity.displayNames(members.stream().map(VaultMember::getUserId).toList());
        return members.stream().map(m -> MemberResponse.of(m, names.get(m.getUserId()))).toList();
    }

    @PostMapping("/vaults/{vaultId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse invite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                                 @Valid @RequestBody InviteRequest req) {
        return MemberResponse.of(vaults.invite(vaultId, CurrentUser.id(jwt), req.email()));
    }

    @DeleteMapping("/vaults/{vaultId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId, @PathVariable UUID userId) {
        vaults.removeMember(vaultId, CurrentUser.id(jwt), userId);
    }

    @PostMapping("/vaults/{vaultId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        vaults.leave(vaultId, CurrentUser.id(jwt));
    }

    @GetMapping("/invitations")
    public List<MemberResponse> invitations(@AuthenticationPrincipal Jwt jwt) {
        return vaults.pendingInvitations(CurrentUser.id(jwt)).stream().map(MemberResponse::of).toList();
    }

    @PostMapping("/invitations/{vaultId}/accept")
    public MemberResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        return MemberResponse.of(vaults.respondToInvitation(vaultId, CurrentUser.id(jwt), true));
    }

    @PostMapping("/invitations/{vaultId}/decline")
    public MemberResponse decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        return MemberResponse.of(vaults.respondToInvitation(vaultId, CurrentUser.id(jwt), false));
    }

    public record CreateVaultRequest(@NotNull Vault.Type type, @NotBlank @Size(max = 120) String name) {}

    public record RenameVaultRequest(@NotBlank @Size(max = 120) String name) {}

    public record InviteRequest(@NotBlank @Email String email) {}

    public record VaultResponse(UUID id, Vault.Type type, String name, UUID ownerId, Vault.State state, Instant createdAt) {
        static VaultResponse of(Vault v) {
            return new VaultResponse(v.getId(), v.getType(), v.getName(), v.getOwnerId(), v.getState(), v.getCreatedAt());
        }
    }

    /** {@code displayName} is set on the member list; a deleted account shows as "Former member". */
    public record MemberResponse(UUID vaultId, UUID userId, VaultMember.Role role, VaultMember.Status status,
                                 Instant invitedAt, Instant joinedAt, String displayName) {
        static MemberResponse of(VaultMember m) {
            return of(m, null);
        }

        static MemberResponse of(VaultMember m, String displayName) {
            return new MemberResponse(m.getVaultId(), m.getUserId(), m.getRole(), m.getStatus(), m.getInvitedAt(),
                    m.getJoinedAt(), displayName);
        }
    }
}
