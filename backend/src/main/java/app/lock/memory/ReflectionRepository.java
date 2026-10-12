package app.lock.memory;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReflectionRepository extends JpaRepository<Reflection, UUID> {

    List<Reflection> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);
}
