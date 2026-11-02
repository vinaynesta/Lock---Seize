package app.lock.identity;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Approving a new device for Sealed Vault keys (roadmap 3.6e). Every call is scoped to the caller's account. */
@RestController
public class DeviceApprovalController {

    private final DeviceApprovalService approvals;

    public DeviceApprovalController(DeviceApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping("/account/device-approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalResponse request(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewApprovalBody req) {
        return ApprovalResponse.of(approvals.request(CurrentUser.id(jwt), req.requestKey(), req.deviceName()));
    }

    @GetMapping("/account/device-approvals")
    public List<ApprovalResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return approvals.pending(CurrentUser.id(jwt)).stream().map(ApprovalResponse::of).toList();
    }

    @PostMapping("/account/device-approvals/{id}/approve")
    public ApprovalResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ApproveBody req) {
        return ApprovalResponse.of(approvals.approve(CurrentUser.id(jwt), id, req.sealedSecret()));
    }

    /** 200 with the sealed key once approved (then gone), 202 while still waiting. */
    @PostMapping("/account/device-approvals/{id}/collect")
    public ResponseEntity<CollectResponse> collect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return approvals.collect(CurrentUser.id(jwt), id)
                .map(s -> ResponseEntity.ok(new CollectResponse(Base64.getEncoder().encodeToString(s))))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    @DeleteMapping("/account/device-approvals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        approvals.cancel(CurrentUser.id(jwt), id);
    }

    public record NewApprovalBody(@NotBlank String requestKey, @Size(max = 100) String deviceName) {}

    public record ApproveBody(@NotBlank String sealedSecret) {}

    public record CollectResponse(String sealedSecret) {}

    /** {@code fingerprint} of the one-time key: the approving device compares it with the QR code. */
    public record ApprovalResponse(UUID id, String requestKey, String fingerprint, String deviceName, boolean approved,
                                   Instant createdAt, Instant expiresAt) {
        static ApprovalResponse of(DeviceApproval a) {
            return new ApprovalResponse(a.getId(), Base64.getEncoder().encodeToString(a.getRequestKey()),
                    KeyBundleService.fingerprint(a.getRequestKey()), a.getDeviceName(), a.getApprovedAt() != null,
                    a.getCreatedAt(), a.getExpiresAt());
        }
    }
}
