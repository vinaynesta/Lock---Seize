package app.lock.memory;

import app.lock.common.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export anytime in open formats: the answer to "what if Lock shuts down?" (feasibility §4).
 * Returns the vault's memories as plain JSON. Media is fetched through the regular download URLs.
 * A bundled ZIP export (JSON + original media) is a natural follow-up worker job.
 */
@RestController
public class ExportController {

    private static final int EXPORT_LIMIT = 200;

    private final MemoryService memories;

    public ExportController(MemoryService memories) {
        this.memories = memories;
    }

    @GetMapping("/vaults/{vaultId}/export")
    public VaultExport export(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId) {
        UUID userId = CurrentUser.id(jwt);
        List<ExportedMemory> exported = memories.timeline(vaultId, userId, null, null, null, EXPORT_LIMIT).stream()
                .map(summary -> {
                    MemoryView full = memories.get(summary.id(), userId);
                    List<MemoryService.ReflectionView> refl = full.state().isLocked() && !full.sealedCapsule()
                            ? memories.listReflections(full.id(), userId)
                            : List.of();
                    return new ExportedMemory(full, refl);
                })
                .toList();
        return new VaultExport("lock-export/v1", vaultId, Instant.now(), exported);
    }

    public record VaultExport(String format, UUID vaultId, Instant exportedAt, List<ExportedMemory> memories) {}

    public record ExportedMemory(MemoryView memory, List<MemoryService.ReflectionView> reflections) {}
}
