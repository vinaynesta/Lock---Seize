package app.lock.notification;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByPushToken(String pushToken);

    List<Device> findByUserId(UUID userId);

    @Query("select d from Device d where d.userId in :userIds")
    List<Device> findByUserIdIn(Collection<UUID> userIds);

    @Modifying
    @Query("delete from Device d where d.pushToken in :tokens")
    int deleteByPushTokenIn(Collection<String> tokens);

    @Modifying
    @Query("delete from Device d where d.userId = :userId")
    int deleteByUserId(UUID userId);
}
