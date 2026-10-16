package app.lock.vault;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VaultMemberRepository extends JpaRepository<VaultMember, VaultMember.Id> {

    @Query("select m from VaultMember m where m.id.vaultId = :vaultId order by m.invitedAt nulls first, m.joinedAt")
    List<VaultMember> findByVaultId(UUID vaultId);

    @Query("select m from VaultMember m where m.id.userId = :userId and m.status = :status")
    List<VaultMember> findByUserIdAndStatus(UUID userId, VaultMember.Status status);

    @Query("select count(m) from VaultMember m where m.id.vaultId = :vaultId and m.status = app.lock.vault.VaultMember.Status.ACTIVE")
    long countActive(UUID vaultId);
}
