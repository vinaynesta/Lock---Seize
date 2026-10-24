package app.lock.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Idempotent enqueue: a second insert with the same dedupe key does nothing. */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (id, user_id, vault_id, category, title, body, data, dedupe_key, group_key,
                                       send_at, status, attempts, created_at)
            VALUES (:id, :userId, :vaultId, :category, :title, :body, cast(:data as jsonb), :dedupeKey, :groupKey,
                    :sendAt, 'PENDING', 0, :now)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int enqueue(UUID id, UUID userId, UUID vaultId, String category, String title, String body, String data,
                String dedupeKey, String groupKey, Instant sendAt, Instant now);

    /** Due notifications, claimed with SKIP LOCKED so instances never send the same one twice. */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PENDING' AND send_at <= :now
            ORDER BY send_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> lockDue(Instant now, int limit);

    @Modifying
    @Query("""
            update Notification n set n.status = app.lock.notification.Notification.Status.CANCELLED
            where n.groupKey = :groupKey and n.status = app.lock.notification.Notification.Status.PENDING
            """)
    int cancelGroup(String groupKey);

    @Modifying
    @Query("""
            update Notification n set n.status = app.lock.notification.Notification.Status.CANCELLED
            where n.userId = :userId and n.status = app.lock.notification.Notification.Status.PENDING
            """)
    int cancelAllFor(UUID userId);

    List<Notification> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /**
     * Users with a device and at least one locked memory from this calendar day in an earlier year,
     * for the daily "On this day" nudge. No content is read: the message only says there is one.
     */
    @Query(value = """
            SELECT DISTINCT d.user_id FROM devices d
            JOIN vault_members vm ON vm.user_id = d.user_id AND vm.status = 'ACTIVE'
            JOIN memories m ON m.vault_id = vm.vault_id
            WHERE m.state IN ('LOCKED', 'ARCHIVED')
              AND extract(month FROM m.occurred_at AT TIME ZONE 'UTC') = :month
              AND extract(day FROM m.occurred_at AT TIME ZONE 'UTC') = :day
              AND m.occurred_at < :before
              AND (m.reveal_at IS NULL OR m.reveal_at <= :before)
            """, nativeQuery = true)
    List<UUID> usersWithOnThisDay(int month, int day, Instant before);
}
