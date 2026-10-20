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

    /** Vaults whose data key is not yet wrapped by {@code kekId}, row-locked for the re-wrap job. */
    @Query(value = """
            SELECT * FROM vaults
            WHERE kek_id IS DISTINCT FROM :kekId
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Vault> lockNotWrappedBy(String kekId, int limit);

    @Query(value = "SELECT count(*) FROM vaults WHERE kek_id IS DISTINCT FROM :kekId", nativeQuery = true)
    long countNotWrappedBy(String kekId);
}
