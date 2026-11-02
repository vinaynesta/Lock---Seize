package app.lock.vault;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VaultMemberKeyRepository extends JpaRepository<VaultMemberKey, VaultMemberKey.Id> {

    @Query("select k from VaultMemberKey k where k.id.vaultId = :vaultId and k.id.userId = :userId order by k.id.keyVersion")
    List<VaultMemberKey> findForMember(UUID vaultId, UUID userId);

    @Query("select k from VaultMemberKey k where k.id.vaultId = :vaultId and k.id.keyVersion = :version")
    List<VaultMemberKey> findForVersion(UUID vaultId, int version);

    @Query("select k from VaultMemberKey k where k.id.vaultId = :vaultId")
    List<VaultMemberKey> findForVault(UUID vaultId);

    @Query("select count(k) > 0 from VaultMemberKey k where k.id.vaultId = :vaultId and k.id.userId = :userId and k.id.keyVersion = :version")
    boolean hasKey(UUID vaultId, UUID userId, int version);

    @Modifying
    @Query("delete from VaultMemberKey k where k.id.userId = :userId")
    int deleteAllForUser(UUID userId);
}
