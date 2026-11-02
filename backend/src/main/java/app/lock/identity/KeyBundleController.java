package app.lock.identity;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own Sealed Vault key bundle (roadmap 3.6). Blobs are base64; the server can't open them. */
@RestController
public class KeyBundleController {

    private final KeyBundleService bundles;

    public KeyBundleController(KeyBundleService bundles) {
        this.bundles = bundles;
    }

    @PostMapping("/account/key-bundle")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyBundleResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest req,
                                    @RequestHeader(name = MfaController.HEADER, required = false) String mfaCode) {
        return KeyBundleResponse.of(bundles.create(CurrentUser.id(jwt), req.password(), mfaCode,
                new KeyBundleService.NewBundle(req.publicKey(), req.wrappedByPassphrase(), req.passphraseKdf(),
                        req.wrappedByRecovery(), req.recoveryKdf())));
    }

    @GetMapping("/account/key-bundle")
    public KeyBundleResponse get(@AuthenticationPrincipal Jwt jwt) {
        return KeyBundleResponse.of(bundles.get(CurrentUser.id(jwt)));
    }

    @PutMapping("/account/key-bundle/passphrase")
    public KeyBundleResponse changePassphrase(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PassphraseRequest req,
                                              @RequestHeader(name = MfaController.HEADER, required = false) String mfaCode) {
        return KeyBundleResponse.of(bundles.changePassphrase(CurrentUser.id(jwt), req.password(), mfaCode,
                req.wrappedByPassphrase(), req.passphraseKdf()));
    }

    public record CreateRequest(@NotBlank String password, @NotBlank String publicKey, @NotBlank String wrappedByPassphrase,
                                @NotNull Map<String, Object> passphraseKdf, @NotBlank String wrappedByRecovery,
                                @NotNull Map<String, Object> recoveryKdf) {}

    public record PassphraseRequest(@NotBlank String password, @NotBlank String wrappedByPassphrase,
                                    @NotNull Map<String, Object> passphraseKdf) {}

    public record KeyBundleResponse(String publicKey, String fingerprint, String wrappedByPassphrase,
                                    Map<String, Object> passphraseKdf, String wrappedByRecovery,
                                    Map<String, Object> recoveryKdf, Instant createdAt, Instant updatedAt) {
        static KeyBundleResponse of(KeyBundle b) {
            Base64.Encoder b64 = Base64.getEncoder();
            return new KeyBundleResponse(b64.encodeToString(b.getPublicKey()), KeyBundleService.fingerprint(b.getPublicKey()),
                    b64.encodeToString(b.getWrappedByPassphrase()), b.getPassphraseKdf(),
                    b64.encodeToString(b.getWrappedByRecovery()), b.getRecoveryKdf(), b.getCreatedAt(), b.getUpdatedAt());
        }
    }
}
