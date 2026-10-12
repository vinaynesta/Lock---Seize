package app.lock.vault;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VaultRepository extends JpaRepository<Vault, UUID> {

    @Query("""
            select v from Vault v
            where v.id in (select m.id.vaultId from VaultMember m
                           where m.id.userId = :userId and m.status = app.lock.vault.VaultMember.Status.ACTIVE)
            order by v.createdAt
            """)
    List<Vault> findActiveForUser(UUID userId);
}
