package app.lock.export;

import app.lock.common.CurrentUser;
import app.lock.identity.MfaController;
import app.lock.identity.MfaService;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** ZIP exports with original media (roadmap 2.3). The JSON-only {@code GET /vaults/{id}/export} remains. */
@RestController
public class ExportJobController {

    private final ExportService exports;
    private final MfaService mfa;

    public ExportJobController(ExportService exports, MfaService mfa) {
        this.exports = exports;
        this.mfa = mfa;
    }

    @PostMapping("/vaults/{vaultId}/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportResponse request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                                  @RequestHeader(name = MfaController.HEADER, required = false) String mfaCode) {
        mfa.require(CurrentUser.id(jwt), mfaCode);
        return ExportResponse.of(exports.request(vaultId, CurrentUser.id(jwt)));
    }

    @GetMapping("/vaults/{vaultId}/exports")
    public List<ExportResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        return exports.list(vaultId, CurrentUser.id(jwt)).stream().map(ExportResponse::of).toList();
    }

    @GetMapping("/exports/{exportId}")
    public ExportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exportId) {
        return ExportResponse.of(exports.get(exportId, CurrentUser.id(jwt)));
    }

    @GetMapping("/exports/{exportId}/download")
    public DownloadResponse download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exportId) {
        return new DownloadResponse(exports.downloadUrl(exportId, CurrentUser.id(jwt)));
    }

    public record ExportResponse(UUID id, UUID vaultId, VaultExport.Status status, Long sizeBytes, Integer memoryCount,
                                 Integer assetCount, String error, Instant requestedAt, Instant completedAt, Instant expiresAt) {
        static ExportResponse of(VaultExport e) {
            return new ExportResponse(e.getId(), e.getVaultId(), e.getStatus(), e.getSizeBytes(), e.getMemoryCount(),
                    e.getAssetCount(), e.getError(), e.getRequestedAt(), e.getCompletedAt(), e.getExpiresAt());
        }
    }

    public record DownloadResponse(URI url) {}
}
