package app.lock.notification;

import app.lock.common.ApiException;
import app.lock.vault.VaultMember;
import app.lock.vault.VaultMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Push notifications (roadmap 2.7): device registration, preferences, a queue of notifications
 * (immediate or scheduled), and the sender that drains it.
 *
 * <p>Notifications never carry memory content: it is encrypted, and push providers are third
 * parties. Titles and bodies are generic ("Your memory is locked"); {@code data} holds only ids the
 * app uses to open the right screen.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final int MAX_ATTEMPTS = 5;

    private final NotificationRepository notifications;
    private final DeviceRepository devices;
    private final NotificationPreferencesRepository preferences;
    private final VaultMemberRepository members;
    private final PushSender push;
    private final JsonMapper json;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, DeviceRepository devices,
                               NotificationPreferencesRepository preferences, VaultMemberRepository members,
                               PushSender push, JsonMapper json, Clock clock) {
        this.notifications = notifications;
        this.devices = devices;
        this.preferences = preferences;
        this.members = members;
        this.push = push;
        this.json = json;
        this.clock = clock;
    }

    // ---- Queue ----------------------------------------------------------------------------------

    /** What to send. Exactly one of {@code userId} and {@code vaultId}. */
    public record Draft(UUID userId, UUID vaultId, NotificationCategory category, String title, String body,
                        Map<String, Object> data, String dedupeKey, String groupKey, Instant sendAt) {

        public static Draft toUser(UUID userId, NotificationCategory category, String title, String body,
                                   Map<String, Object> data, String dedupeKey) {
            return new Draft(userId, null, category, title, body, data, dedupeKey, null, null);
        }

        public Draft at(Instant when) {
            return new Draft(userId, vaultId, category, title, body, data, dedupeKey, groupKey, when);
        }

        public Draft inGroup(String group) {
            return new Draft(userId, vaultId, category, title, body, data, dedupeKey, group, sendAt);
        }
    }

    /** Idempotent on {@code dedupeKey}. A send time in the past (or none) means "as soon as possible". */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(Draft d) {
        Instant now = Instant.now(clock);
        Instant sendAt = d.sendAt() == null || d.sendAt().isBefore(now) ? now : d.sendAt();
        notifications.enqueue(UUID.randomUUID(), d.userId(), d.vaultId(), d.category().name(), d.title(), d.body(),
                json.writeValueAsString(d.data() == null ? Map.of() : d.data()), d.dedupeKey(), d.groupKey(), sendAt, now);
    }

    /** Cancels whatever in the group has not been sent yet, e.g. reminders for a cancelled deletion. */
    @Transactional
    public int cancelGroup(String groupKey) {
        return notifications.cancelGroup(groupKey);
    }

    /** Account deleted: nothing more is sent to this user, and their devices are forgotten. */
    @Transactional
    public void forgetUser(UUID userId) {
        notifications.cancelAllFor(userId);
        devices.deleteByUserId(userId);
    }

    /**
     * Sends due notifications. Each batch is claimed with SKIP LOCKED, so several instances can run
     * this at once. Returns how many notifications were handled.
     */
    @Transactional
    public int sendDue(int batchSize) {
        Instant now = Instant.now(clock);
        List<Notification> due = notifications.lockDue(now, batchSize);
        for (Notification n : due) {
            try {
                deliver(n, now);
            } catch (RuntimeException e) {
                log.warn("Notification {} ({}) failed: {}", n.getId(), n.getCategory(), e.getMessage());
                n.markFailed(e.getMessage(), MAX_ATTEMPTS);
            }
        }
        return due.size();
    }

    private void deliver(Notification n, Instant now) {
        List<UUID> recipients = recipients(n).stream()
                .filter(userId -> !n.getCategory().isOptional() || preferencesOf(userId).allows(n.getCategory()))
                .toList();
        List<Device> targets = recipients.isEmpty() ? List.of() : devices.findByUserIdIn(recipients);
        if (targets.isEmpty()) {
            n.markSkipped(now);
            return;
        }
        Map<String, Object> data = new java.util.HashMap<>(n.getData());
        data.put("category", n.getCategory().name());
        List<PushSender.Message> messages = targets.stream()
                .map(d -> new PushSender.Message(d.getPushToken(), n.getTitle(), n.getBody(), data))
                .toList();
        PushSender.Result result = push.send(messages);
        if (!result.deadTokens().isEmpty()) {
            devices.deleteByPushTokenIn(result.deadTokens());
        }
        n.markSent(now);
    }

    private List<UUID> recipients(Notification n) {
        if (n.getUserId() != null) {
            return List.of(n.getUserId());
        }
        // A vault notification goes to whoever is an active member when it is sent, not when queued.
        return members.findByVaultId(n.getVaultId()).stream()
                .filter(VaultMember::isActive)
                .map(VaultMember::getUserId)
                .toList();
    }

    // ---- Devices --------------------------------------------------------------------------------

    @Transactional
    public Device registerDevice(UUID userId, String pushToken, Device.Platform platform) {
        Instant now = Instant.now(clock);
        return devices.findByPushToken(pushToken)
                .map(d -> {
                    d.refresh(userId, platform, now);
                    return d;
                })
                .orElseGet(() -> devices.save(new Device(userId, pushToken, platform, now)));
    }

    /** Sign-out on a device: stop notifying it. Only the device's own user can remove it. */
    @Transactional
    public void unregisterDevice(UUID userId, String pushToken) {
        devices.findByPushToken(pushToken).filter(d -> d.getUserId().equals(userId)).ifPresent(devices::delete);
    }

    @Transactional(readOnly = true)
    public List<Device> devicesOf(UUID userId) {
        return devices.findByUserId(userId);
    }

    // ---- Preferences ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<NotificationCategory, Boolean> preferences(UUID userId) {
        NotificationPreferences prefs = preferencesOf(userId);
        Map<NotificationCategory, Boolean> out = new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory c : NotificationCategory.values()) {
            out.put(c, prefs.allows(c));
        }
        return out;
    }

    @Transactional
    public Map<NotificationCategory, Boolean> updatePreferences(UUID userId, Map<NotificationCategory, Boolean> changes) {
        Instant now = Instant.now(clock);
        NotificationPreferences prefs = preferences.findById(userId)
                .orElseGet(() -> preferences.save(new NotificationPreferences(userId, now)));
        List<NotificationCategory> refused = new ArrayList<>();
        changes.forEach((category, enabled) -> {
            if (!category.isOptional() && !enabled) {
                refused.add(category);
            } else {
                prefs.set(category, enabled, now);
            }
        });
        if (!refused.isEmpty()) {
            throw ApiException.badRequest("NOT_OPTIONAL", "These notifications are safety features and stay on: " + refused);
        }
        return preferences(userId);
    }

    private NotificationPreferences preferencesOf(UUID userId) {
        return preferences.findById(userId).orElseGet(() -> new NotificationPreferences(userId, Instant.now(clock)));
    }

    /** Test hook. */
    @Transactional(readOnly = true)
    public List<Notification> queuedFor(UUID userId) {
        return notifications.findByUserIdOrderByCreatedAtAsc(userId);
    }

}
