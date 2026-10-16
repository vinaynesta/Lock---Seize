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

    /** Timeline page: newest first, optionally bounded by date and state. */
    @Query("""
            select m from Memory m
            where m.vaultId = :vaultId
              and m.occurredAt >= :from and m.occurredAt < :to
              and (:state is null or m.state = :state)
              and (m.state <> app.lock.memory.MemoryState.DRAFT or m.createdBy = :viewerId)
            order by m.occurredAt desc, m.id desc
            """)
    List<Memory> timeline(UUID vaultId, UUID viewerId, Instant from, Instant to, MemoryState state, Pageable page);

    /** Keyset page in timeline order, strictly after the (occurredAt, id) cursor. Used to walk a whole vault. */
    @Query("""
            select m from Memory m
            where m.vaultId = :vaultId
              and (m.occurredAt < :afterOccurredAt or (m.occurredAt = :afterOccurredAt and m.id < :afterId))
              and (m.state <> app.lock.memory.MemoryState.DRAFT or m.createdBy = :viewerId)
            order by m.occurredAt desc, m.id desc
            """)
    List<Memory> timelineAfter(UUID vaultId, UUID viewerId, Instant afterOccurredAt, UUID afterId, Pageable page);

    @Query("select m from Memory m where m.state = app.lock.memory.MemoryState.SEALING and m.sealingUntil <= :now")
    List<Memory> findSealingDue(Instant now, Pageable page);

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
