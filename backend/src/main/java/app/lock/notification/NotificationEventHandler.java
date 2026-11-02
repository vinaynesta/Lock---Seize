package app.lock.notification;

import app.lock.memory.MemoryRepository;
import app.lock.outbox.OutboxEvent;
import app.lock.outbox.OutboxEventHandler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns domain events into notifications (roadmap 2.7). Enqueueing is idempotent (dedupe keys), so
 * an outbox event handled twice still notifies once.
 */
@Component
class NotificationEventHandler implements OutboxEventHandler {

    static final Duration[] REMINDERS = {Duration.ofDays(7), Duration.ofDays(1)};

    private static final Set<String> HANDLED = Set.of(
            "memory.locked", "vault.member.invited", "asset.rejected", "export.ready",
            "memory.deletion.requested", "memory.deletion.cancelled", "memory.purged",
            "account.deletion.requested", "account.deletion.cancelled", "account.deleted");

    private final NotificationService notifications;
    private final MemoryRepository memories;
    private final Clock clock;

    NotificationEventHandler(NotificationService notifications, MemoryRepository memories, Clock clock) {
        this.notifications = notifications;
        this.memories = memories;
        this.clock = clock;
    }

    @Override
    public boolean supports(String eventType) {
        return HANDLED.contains(eventType);
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) {
        UUID id = event.getAggregateId();
        Map<String, Object> p = event.getPayload();
        switch (event.getEventType()) {
            case "memory.locked" -> memoryLocked(id, p);
            case "vault.member.invited" -> notifications.enqueue(NotificationService.Draft.toUser(uuid(p, "userId"),
                    NotificationCategory.VAULT_INVITATION, "You're invited to a shared vault",
                    "Open Lock to accept or decline.", Map.of("vaultId", id.toString()),
                    "invite:" + event.getId()));
            case "asset.rejected" -> memories.findById(uuid(p, "memoryId")).ifPresent(m -> notifications.enqueue(
                    NotificationService.Draft.toUser(m.getCreatedBy(), NotificationCategory.ASSET_REJECTED,
                            "A file couldn't be added",
                            "One of the files you added failed the safety scan and was removed from your draft.",
                            Map.of("memoryId", m.getId().toString()), "asset-rejected:" + id)));
            case "export.ready" -> notifications.enqueue(NotificationService.Draft.toUser(uuid(p, "userId"),
                    NotificationCategory.EXPORT_READY, "Your export is ready",
                    "Download it from the vault's Export screen within 7 days.",
                    Map.of("exportId", id.toString()), "export-ready:" + id));
            case "memory.deletion.requested" -> reminders(uuid(p, "userId"), "memory-deletion:" + id,
                    Instant.parse((String) p.get("executeAfter")), "A memory will be deleted",
                    Map.of("memoryId", id.toString()));
            case "memory.deletion.cancelled", "memory.purged" -> notifications.cancelGroup("memory-deletion:" + id);
            case "account.deletion.requested" -> reminders(id, "account-deletion:" + id,
                    Instant.parse((String) p.get("executeAfter")), "Your account will be deleted", Map.of());
            case "account.deletion.cancelled" -> notifications.cancelGroup("account-deletion:" + id);
            case "account.deleted" -> notifications.forgetUser(id);
            default -> { }
        }
    }

    private void memoryLocked(UUID memoryId, Map<String, Object> p) {
        if (p.get("createdBy") == null) {
            return; // published before 2.7
        }
        notifications.enqueue(NotificationService.Draft.toUser(uuid(p, "createdBy"), NotificationCategory.MEMORY_LOCKED,
                "Your memory is locked", "It's preserved now. Relive it any time.",
                Map.of("memoryId", memoryId.toString()), "locked:" + memoryId));
        if (p.get("revealAt") != null) {
            // Everyone in the vault when it opens, not when it was sealed.
            notifications.enqueue(new NotificationService.Draft(null, uuid(p, "vaultId"), NotificationCategory.CAPSULE_REVEALED,
                    "A time capsule just opened", "A memory sealed for this day is ready to relive.",
                    Map.of("memoryId", memoryId.toString()), "capsule:" + memoryId, "memory-deletion:" + memoryId,
                    Instant.parse((String) p.get("revealAt"))));
        }
    }

    /** 7 days and 1 day before. A reminder whose time has already passed is not sent. */
    private void reminders(UUID userId, String group, Instant executeAfter, String title, Map<String, Object> data) {
        for (Duration before : REMINDERS) {
            Instant at = executeAfter.minus(before);
            if (at.isBefore(Instant.now(clock))) {
                continue; // e.g. a grace period shorter than 7 days
            }
            long days = before.toDays();
            notifications.enqueue(NotificationService.Draft.toUser(userId, NotificationCategory.DELETION_REMINDER, title,
                            "In %d day%s. You can still cancel it in Lock.".formatted(days, days == 1 ? "" : "s"), data,
                            group + ":" + executeAfter + ":" + days + "d")
                    .inGroup(group).at(at));
        }
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        return UUID.fromString((String) payload.get(key));
    }
}
