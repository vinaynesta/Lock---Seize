package app.lock.account;

import app.lock.common.CurrentUser;
import app.lock.identity.MfaController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account deletion (roadmap 2.5): schedule with the password, see it, or cancel it during the grace period. */
@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/account/deletion-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeletionResponse request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionBody req,
                                    @RequestHeader(name = MfaController.HEADER, required = false) String mfaCode) {
        return DeletionResponse.of(accounts.requestDeletion(CurrentUser.id(jwt), req.password(), mfaCode));
    }

    @GetMapping("/account/deletion-request")
    public DeletionResponse get(@AuthenticationPrincipal Jwt jwt) {
        return DeletionResponse.of(accounts.pending(CurrentUser.id(jwt)));
    }

    @DeleteMapping("/account/deletion-request")
    public DeletionResponse cancel(@AuthenticationPrincipal Jwt jwt) {
        return DeletionResponse.of(accounts.cancelDeletion(CurrentUser.id(jwt)));
    }

    public record DeletionBody(@NotBlank String password) {}

    public record DeletionResponse(UUID id, AccountDeletion.Status status, Instant requestedAt, Instant executeAfter) {
        static DeletionResponse of(AccountDeletion d) {
            return new DeletionResponse(d.getId(), d.getStatus(), d.getRequestedAt(), d.getExecuteAfter());
        }
    }
}
