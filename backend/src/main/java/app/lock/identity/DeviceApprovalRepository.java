package app.lock.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceApprovalRepository extends JpaRepository<DeviceApproval, UUID> {

    @Query("select a from DeviceApproval a where a.userId = :userId and a.approvedAt is null and a.expiresAt > :now order by a.createdAt desc")
    List<DeviceApproval> findPending(UUID userId, Instant now);

    @Query("select count(a) from DeviceApproval a where a.userId = :userId and a.expiresAt > :now")
    long countLive(UUID userId, Instant now);

    @Modifying
    @Query("delete from DeviceApproval a where a.expiresAt <= :now")
    int deleteExpired(Instant now);
}
