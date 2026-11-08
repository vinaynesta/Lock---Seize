package app.lock.vault;

import app.lock.common.CurrentUser;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Wrapped data keys of a sealed vault (roadmap 3.6). Non-members get 404, as for every vault call. */
@RestController
public class SealedVaultKeyController {

    private final SealedVaultKeyService keys;

    public SealedVaultKeyController(SealedVaultKeyService keys) {
        this.keys = keys;
    }

    @GetMapping("/vaults/{vaultId}/keys")
    public KeyStateResponse state(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        return KeyStateResponse.of(keys.state(vaultId, CurrentUser.id(jwt)));
    }

    @PostMapping("/vaults/{vaultId}/keys/{version}")
    public KeyStateResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId, @PathVariable int version,
                                @Valid @RequestBody AddKeysRequest req) {
        return KeyStateResponse.of(keys.addKeys(vaultId, CurrentUser.id(jwt), version, req.keys()));
    }

    /** {@code keys}: recipient user id → base64 wrapped key. */
    public record AddKeysRequest(@NotEmpty Map<UUID, String> keys) {}

    /** {@code wrappedByPublicKey}: the wrapping member's public key when the copy was stored (null on version-1 copies). */
    public record WrappedKeyResponse(int keyVersion, String wrappedKey, UUID wrappedBy, String wrappedByPublicKey,
                                     Instant createdAt) {}

    public record MemberKeyResponse(UUID userId, String publicKey, String fingerprint) {}

    public record KeyStateResponse(UUID vaultId, int currentVersion, List<WrappedKeyResponse> myKeys,
                                   List<MemberKeyResponse> members, Set<UUID> missingCurrent) {
        static KeyStateResponse of(SealedVaultKeyService.KeyState s) {
            Base64.Encoder b64 = Base64.getEncoder();
            return new KeyStateResponse(s.vaultId(), s.currentVersion(),
                    s.myKeys().stream().map(k -> new WrappedKeyResponse(k.getKeyVersion(), b64.encodeToString(k.getWrappedKey()),
                            k.getWrappedBy(), k.getWrappedByPublicKey() == null ? null : b64.encodeToString(k.getWrappedByPublicKey()),
                            k.getCreatedAt())).toList(),
                    s.members().stream().map(m -> new MemberKeyResponse(m.userId(), b64.encodeToString(m.publicKey()), m.fingerprint())).toList(),
                    s.missingCurrent());
        }
    }
}
