package app.lock.identity;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final IdentityService identity;

    public AuthController(IdentityService identity) {
        this.identity = identity;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        identity.register(req.email(), req.password(), req.displayName());
        return TokenResponse.of(identity.login(req.email(), req.password()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return TokenResponse.of(identity.login(req.email(), req.password()));
    }

    /** Rotates the refresh token: the old one stops working and a new pair is returned. */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return TokenResponse.of(identity.refresh(req.refreshToken()));
    }

    /** Revokes the refresh token's family. Unauthenticated so an expired access token can still log out. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        identity.logout(req.refreshToken());
    }

    /** Signs out every other session and returns a new one for this device. */
    @PostMapping("/change-password")
    public TokenResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        return TokenResponse.of(identity.changePassword(CurrentUser.id(jwt), req.currentPassword(), req.newPassword()));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = identity.get(CurrentUser.id(jwt));
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt,
                                String refreshToken, Instant refreshExpiresAt) {
        static TokenResponse of(IdentityService.Session session) {
            return new TokenResponse(session.accessToken().value(), "Bearer", session.accessToken().expiresAt(),
                    session.refreshToken().value(), session.refreshToken().expiresAt());
        }
    }

    public record MeResponse(UUID id, String email, String displayName, Instant createdAt) {}
}
