package app.lock.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /** Row lock so two concurrent confirmations with the same token cannot both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findForUpdate(String tokenHash);

    /** Supersedes every outstanding token of the user: only the newest link works, and none after a change. */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateAll(UUID userId, Instant now);

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
