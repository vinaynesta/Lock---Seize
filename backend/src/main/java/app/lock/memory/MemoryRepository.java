package app.lock.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MemoryRepository extends JpaRepository<Memory, UUID> {

    /** Row lock that serialises lock/seal against concurrent asset changes on the same memory. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Memory m where m.id = :id")
    Optional<Memory> findForUpdate(UUID id);

    Optional<Memory> findByCreatedByAndIdempotencyKey(UUID createdBy, String idempotencyKey);

    /**
     * Timeline page: newest first, optionally bounded by date and state. Keyset pagination: pass the
     * (occurredAt, id) of the last memory already seen as the cursor, or nulls for the first page.
     */
    @Query("""
            select m from Memory m
            where m.vaultId = :vaultId
              and m.occurredAt >= :from and m.occurredAt < :to
              and (:state is null or m.state = :state)
              and (m.state <> app.lock.memory.MemoryState.DRAFT or m.createdBy = :viewerId)
              and (cast(:afterId as uuid) is null
                   or m.occurredAt < :afterOccurredAt or (m.occurredAt = :afterOccurredAt and m.id < :afterId))
            order by m.occurredAt desc, m.id desc
            """)
    List<Memory> timeline(UUID vaultId, UUID viewerId, Instant from, Instant to, MemoryState state,
                          Instant afterOccurredAt, UUID afterId, Pageable page);

    /**
     * Due seals, row-locked. SKIP LOCKED lets several app instances run the job at once: each claims
     * a disjoint batch instead of sealing (and auditing) the same memory twice.
     */
    @Query(value = """
            SELECT * FROM memories
            WHERE state = 'SEALING' AND sealing_until <= :now
            ORDER BY sealing_until
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Memory> lockSealingDue(Instant now, int limit);

    /** Oldest cooling-off end that has already passed: how far behind the seal job is. */
    @Query(value = "SELECT min(sealing_until) FROM memories WHERE state = 'SEALING' AND sealing_until <= :now", nativeQuery = true)
    Instant oldestOverdueSeal(Instant now);

    /** "On this day": locked memories from the same calendar day in earlier years, across the user's vaults. */
    @Query(value = """
            SELECT m.* FROM memories m
            JOIN vault_members vm ON vm.vault_id = m.vault_id AND vm.user_id = :userId AND vm.status = 'ACTIVE'
            WHERE m.state IN ('LOCKED', 'ARCHIVED')
              AND extract(month FROM m.occurred_at AT TIME ZONE 'UTC') = :month
              AND extract(day FROM m.occurred_at AT TIME ZONE 'UTC') = :day
              AND m.occurred_at < :before
            ORDER BY m.occurred_at DESC
            LIMIT 50
            """, nativeQuery = true)
    List<Memory> onThisDay(UUID userId, int month, int day, Instant before);

    /** Memories from around the same time, for the Relive "from the same period" strip. */
    @Query("""
            select m from Memory m
            where m.vaultId = :vaultId and m.id <> :excludeId
              and m.state in (app.lock.memory.MemoryState.LOCKED, app.lock.memory.MemoryState.ARCHIVED)
              and m.occurredAt between :from and :to
            order by m.occurredAt desc
            """)
    List<Memory> samePeriod(UUID vaultId, UUID excludeId, Instant from, Instant to, Pageable page);
}
