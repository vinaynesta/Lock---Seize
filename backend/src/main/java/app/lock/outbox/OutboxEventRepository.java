package app.lock.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** SKIP LOCKED lets several app instances drain the outbox concurrently without double-dispatch. */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE processed_at IS NULL AND attempts < :maxAttempts
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int maxAttempts, int limit);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    @Query(value = "SELECT count(*) FROM outbox_events WHERE processed_at IS NULL AND attempts < :maxAttempts", nativeQuery = true)
    long countPending(int maxAttempts);

    /** Events that used up their retries and need a human. */
    @Query(value = "SELECT count(*) FROM outbox_events WHERE processed_at IS NULL AND attempts >= :maxAttempts", nativeQuery = true)
    long countDead(int maxAttempts);

    @Query(value = "SELECT min(created_at) FROM outbox_events WHERE processed_at IS NULL AND attempts < :maxAttempts", nativeQuery = true)
    java.time.Instant oldestPending(int maxAttempts);
}
