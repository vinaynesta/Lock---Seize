package app.lock.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountDeletionRepository extends JpaRepository<AccountDeletion, UUID> {

    Optional<AccountDeletion> findByUserIdAndStatus(UUID userId, AccountDeletion.Status status);

    /** Due deletions, claimed with SKIP LOCKED so two instances never delete the same account. */
    @Query(value = """
            SELECT * FROM account_deletions
            WHERE status = 'PENDING' AND execute_after <= :now
            ORDER BY execute_after
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AccountDeletion> lockDue(Instant now, int limit);
}
