package app.lock.identity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {

    /** Row lock so two requests with the same code cannot both pass the replay check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMfa m where m.userId = :userId")
    Optional<UserMfa> findForUpdate(UUID userId);
}

interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MfaRecoveryCode c where c.userId = :userId and c.codeHash = :codeHash and c.usedAt is null")
    Optional<MfaRecoveryCode> findUnusedForUpdate(UUID userId, String codeHash);

    @Query("select count(c) from MfaRecoveryCode c where c.userId = :userId and c.usedAt is null")
    long countUnused(UUID userId);

    @Modifying
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    int deleteByUserId(UUID userId);
}
