package app.lock.notification;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {}
