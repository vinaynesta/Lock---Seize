package app.lock.identity;

import app.lock.common.CurrentUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final IdentityService identity;
    private final PasswordResetService resets;
    private final RefreshTokenService refreshTokens;

    public AuthController(IdentityService identity, PasswordResetService resets, RefreshTokenService refreshTokens) {
        this.identity = identity;
        this.resets = resets;
        this.refreshTokens = refreshTokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req,
                                  @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        identity.register(req.email(), req.password(), req.displayName());
        return TokenResponse.of(identity.login(req.email(), req.password(), userAgent));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req,
                               @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return TokenResponse.of(identity.login(req.email(), req.password(), userAgent));
    }

    /** With two-step verification on, login returns only an {@code mfaToken}; send it here with a code. */
    @PostMapping("/login/mfa")
    public TokenResponse loginMfa(@Valid @RequestBody MfaLoginRequest req,
                                  @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return TokenResponse.of(identity.completeMfaLogin(req.mfaToken(), req.code(), userAgent));
    }

    /** Rotates the refresh token: the old one stops working and a new pair is returned. */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req,
                                 @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return TokenResponse.of(identity.refresh(req.refreshToken(), userAgent));
    }

    /** Revokes the refresh token's family. Unauthenticated so an expired access token can still log out. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        identity.logout(req.refreshToken());
    }

    /**
     * Emails a reset link if the address has an account. Always 202 with no body, so the response
     * does not reveal whether the account exists.
     */
    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody PasswordResetRequest req) {
        resets.request(req.email());
    }

    /** Sets a new password with the emailed token and signs out every session. The user then signs in. */
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirm req) {
        resets.confirm(req.token(), req.newPassword());
    }

    /** Signs out every other session and returns a new one for this device. */
    @PostMapping("/change-password")
    public TokenResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req,
                                        @RequestHeader(name = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return TokenResponse.of(identity.changePassword(CurrentUser.id(jwt), req.currentPassword(), req.newPassword(), userAgent));
    }

    /** Devices signed in to this account, newest activity first, each with the client it identified as. */
    @GetMapping("/sessions")
    public java.util.List<RefreshTokenService.SessionInfo> sessions(@AuthenticationPrincipal Jwt jwt) {
        return refreshTokens.sessions(CurrentUser.id(jwt));
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

    public record MfaLoginRequest(@NotBlank @Size(max = 200) String mfaToken, @NotBlank @Size(max = 20) String code) {}

    public record PasswordResetRequest(@NotBlank @Size(max = 320) String email) {}

    public record PasswordResetConfirm(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 128) String newPassword) {}

    /**
     * A session, or, when two-step verification is on and the password was right, only
     * {@code mfaRequired=true} with an {@code mfaToken} to send to {@code /auth/login/mfa}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt,
                                String refreshToken, Instant refreshExpiresAt,
                                Boolean mfaRequired, String mfaToken, Instant mfaTokenExpiresAt) {
        static TokenResponse of(IdentityService.Session session) {
            if (session.mfaRequired()) {
                return new TokenResponse(null, null, null, null, null, true,
                        session.mfaTicket().value(), session.mfaTicket().expiresAt());
            }
            return new TokenResponse(session.accessToken().value(), "Bearer", session.accessToken().expiresAt(),
                    session.refreshToken().value(), session.refreshToken().expiresAt(), null, null, null);
        }
    }

    public record MeResponse(UUID id, String email, String displayName, Instant createdAt) {}
}
