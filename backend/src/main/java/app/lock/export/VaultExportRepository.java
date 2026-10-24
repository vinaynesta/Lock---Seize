package app.lock.export;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VaultExportRepository extends JpaRepository<VaultExport, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from VaultExport e where e.id = :id")
    Optional<VaultExport> findForUpdate(UUID id);

    List<VaultExport> findTop20ByVaultIdAndRequestedByOrderByRequestedAtDesc(UUID vaultId, UUID requestedBy);

    List<VaultExport> findByRequestedBy(UUID requestedBy);

    boolean existsByVaultIdAndRequestedByAndStatus(UUID vaultId, UUID requestedBy, VaultExport.Status status);

    /** READY exports past their expiry, claimed with SKIP LOCKED. */
    @Query(value = """
            SELECT * FROM vault_exports
            WHERE status = 'READY' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VaultExport> lockExpired(Instant now, int limit);

    /** QUEUED exports older than {@code before}: the outbox gave up on them, or the worker died mid-build. */
    @Query(value = """
            SELECT * FROM vault_exports
            WHERE status = 'QUEUED' AND requested_at < :before
            ORDER BY requested_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VaultExport> lockStuck(Instant before, int limit);
}
