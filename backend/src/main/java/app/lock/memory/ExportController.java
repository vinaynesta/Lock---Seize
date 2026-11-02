package app.lock.memory;

import app.lock.common.CurrentUser;
import app.lock.identity.MfaController;
import app.lock.identity.MfaService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export anytime in open formats: the answer to "what if Lock shuts down?" (feasibility §4).
 * Returns the vault's memories as plain JSON. Media is fetched through the regular download URLs.
 * A bundled ZIP export (JSON + original media) is a natural follow-up worker job.
 */
@RestController
public class ExportController {

    private static final int PAGE_SIZE = 200;

    private final MemoryService memories;
    private final MfaService mfa;

    public ExportController(MemoryService memories, MfaService mfa) {
        this.memories = memories;
        this.mfa = mfa;
    }

    @GetMapping("/vaults/{vaultId}/export")
    public VaultExport export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                              @RequestHeader(name = MfaController.HEADER, required = false) String mfaCode) {
        UUID userId = CurrentUser.id(jwt);
        mfa.require(userId, mfaCode);
        // Walk the whole vault page by page: an export must never be silently truncated.
        List<ExportedMemory> exported = new ArrayList<>();
        TimelineCursor cursor = null;
        do {
            MemoryService.TimelinePage page = memories.timeline(vaultId, userId, null, null, null, cursor, PAGE_SIZE);
            for (MemoryView summary : page.memories()) {
                MemoryView full = memories.get(summary.id(), userId);
                List<MemoryService.ReflectionView> refl = full.state().isLocked() && !full.sealedCapsule()
                        ? memories.listReflections(full.id(), userId)
                        : List.of();
                exported.add(new ExportedMemory(full, refl));
            }
            cursor = page.next();
        } while (cursor != null);
        return new VaultExport("lock-export/v1", vaultId, Instant.now(), exported);
    }

    public record VaultExport(String format, UUID vaultId, Instant exportedAt, List<ExportedMemory> memories) {}

    public record ExportedMemory(MemoryView memory, List<MemoryService.ReflectionView> reflections) {}
}
