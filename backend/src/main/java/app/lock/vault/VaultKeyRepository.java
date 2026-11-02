package app.lock.vault;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VaultKeyRepository extends JpaRepository<VaultKey, VaultKey.Id> {

    @Query("select k from VaultKey k where k.id.vaultId = :vaultId")
    List<VaultKey> findByVaultId(UUID vaultId);

    /** Keys not yet wrapped by {@code kekId}, row-locked for the re-wrap job. */
    @Query(value = """
            SELECT * FROM vault_keys
            WHERE kek_id IS DISTINCT FROM :kekId
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<VaultKey> lockNotWrappedBy(String kekId, int limit);

    @Query(value = "SELECT count(*) FROM vault_keys WHERE kek_id IS DISTINCT FROM :kekId", nativeQuery = true)
    long countNotWrappedBy(String kekId);
}
