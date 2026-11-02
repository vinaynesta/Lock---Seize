package app.lock.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeyBundleRepository extends JpaRepository<KeyBundle, UUID> {

    List<KeyBundle> findByUserIdIn(Collection<UUID> userIds);
}
