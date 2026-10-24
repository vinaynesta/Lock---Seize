package app.lock.identity;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-step verification (roadmap 3.1). Once on, sensitive calls (deletion requests, exports) need the
 * current code in an {@code X-MFA-Code} header, and sign-in needs it as a second step.
 */
@RestController
@RequestMapping("/account/mfa")
public class MfaController {

    public static final String HEADER = "X-MFA-Code";

    private final MfaService mfa;

    public MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    @GetMapping
    public MfaService.Status status(@AuthenticationPrincipal Jwt jwt) {
        return mfa.status(CurrentUser.id(jwt));
    }

    /** Returns a secret to add to an authenticator app. MFA is not on until {@code /confirm}. */
    @PostMapping("/setup")
    public MfaService.Setup setUp(@AuthenticationPrincipal Jwt jwt) {
        return mfa.setUp(CurrentUser.id(jwt));
    }

    /** Turns MFA on with a first code. The recovery codes in the response are never shown again. */
    @PostMapping("/confirm")
    public RecoveryCodes confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest req) {
        return new RecoveryCodes(mfa.confirm(CurrentUser.id(jwt), req.code()));
    }

    @PostMapping("/recovery-codes")
    public RecoveryCodes regenerate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest req) {
        return new RecoveryCodes(mfa.regenerateRecoveryCodes(CurrentUser.id(jwt), req.code()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest req) {
        mfa.disable(CurrentUser.id(jwt), req.code());
    }

    public record CodeRequest(@NotBlank @Size(max = 20) String code) {}

    public record RecoveryCodes(List<String> recoveryCodes) {}
}
