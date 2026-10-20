package app.lock.memory;

import app.lock.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately has no {@code DELETE /memories/{id}} for locked memories (design doc §24). Drafts
 * can be discarded; locked memories go through the delayed deletion-request flow.
 */
@RestController
public class MemoryController {

    private final MemoryService memories;

    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @PostMapping("/vaults/{vaultId}/memories")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryView create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                             @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey,
                             @Valid @RequestBody MemoryRequest req) {
        return memories.create(vaultId, CurrentUser.id(jwt), req.toContent(), idempotencyKey);
    }

    /**
     * Timeline, newest first. The body stays a plain array; when more memories exist the response
     * carries a {@code Next-Cursor} header to pass back as {@code ?cursor=} for the next page.
     */
    @GetMapping("/vaults/{vaultId}/memories")
    public ResponseEntity<List<MemoryView>> timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID vaultId,
                                                     @RequestParam(required = false) Instant from,
                                                     @RequestParam(required = false) Instant to,
                                                     @RequestParam(required = false) MemoryState state,
                                                     @RequestParam(required = false) @Size(max = 200) String cursor,
                                                     @RequestParam(defaultValue = "50") int limit) {
        MemoryService.TimelinePage page = memories.timeline(vaultId, CurrentUser.id(jwt), from, to, state,
                cursor == null || cursor.isBlank() ? null : TimelineCursor.decode(cursor), limit);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (page.next() != null) {
            response.header(NEXT_CURSOR, page.next().encode());
        }
        return response.body(page.memories());
    }

    public static final String NEXT_CURSOR = "Next-Cursor";

    @GetMapping("/memories/{memoryId}")
    public MemoryView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.get(memoryId, CurrentUser.id(jwt));
    }

    /** Edits are allowed only while the memory is a DRAFT. */
    @PatchMapping("/memories/{memoryId}")
    public MemoryView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId,
                             @Valid @RequestBody MemoryRequest req) {
        return memories.updateDraft(memoryId, CurrentUser.id(jwt), req.toContent());
    }

    @DeleteMapping("/memories/{memoryId}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        memories.discardDraft(memoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/memories/{memoryId}/lock")
    public MemoryView lock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.lock(memoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/memories/{memoryId}/lock/cancel")
    public MemoryView cancelLock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.cancelLock(memoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/memories/{memoryId}/archive")
    public MemoryView archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.archive(memoryId, CurrentUser.id(jwt), true);
    }

    @PostMapping("/memories/{memoryId}/unarchive")
    public MemoryView unarchive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.archive(memoryId, CurrentUser.id(jwt), false);
    }

    @GetMapping("/memories/{memoryId}/relive")
    public MemoryService.ReliveView relive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.relive(memoryId, CurrentUser.id(jwt));
    }

    @GetMapping("/memories/on-this-day")
    public List<MemoryView> onThisDay(@AuthenticationPrincipal Jwt jwt) {
        return memories.onThisDay(CurrentUser.id(jwt));
    }

    @GetMapping("/memories/{memoryId}/reflections")
    public List<MemoryService.ReflectionView> reflections(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.listReflections(memoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/memories/{memoryId}/reflections")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryService.ReflectionView addReflection(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId,
                                                      @Valid @RequestBody ReflectionRequest req) {
        return memories.addReflection(memoryId, CurrentUser.id(jwt), req.body());
    }

    @GetMapping("/memories/{memoryId}/integrity")
    public MemoryService.IntegrityReport integrity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.verifyIntegrity(memoryId, CurrentUser.id(jwt));
    }

    @PostMapping("/memories/{memoryId}/deletion-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MemoryService.DeletionView requestDeletion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId,
                                                      @Valid @RequestBody DeletionRequestBody req) {
        return memories.requestDeletion(memoryId, CurrentUser.id(jwt), req.password(), req.reason());
    }

    @DeleteMapping("/memories/{memoryId}/deletion-request")
    public MemoryService.DeletionView cancelDeletion(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memoryId) {
        return memories.cancelDeletion(memoryId, CurrentUser.id(jwt));
    }

    public record MemoryRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 20_000) String thought,
            @Size(max = 200) String feeling,
            @Size(max = 300) String location,
            Instant occurredAt,
            Instant revealAt) {

        MemoryService.Content toContent() {
            return new MemoryService.Content(title, thought, feeling, location, occurredAt, revealAt);
        }
    }

    public record ReflectionRequest(@NotBlank @Size(max = 10_000) String body) {}

    public record DeletionRequestBody(@NotBlank String password, @Size(max = 500) String reason) {}
}
