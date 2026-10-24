package app.lock.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Sends due notifications, and queues the daily "On this day" nudge. */
@Component
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);
    private static final int BATCH = 200;

    private final NotificationService notifications;
    private final NotificationRepository repository;
    private final Clock clock;

    NotificationJobs(NotificationService notifications, NotificationRepository repository, Clock clock) {
        this.notifications = notifications;
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    void send() {
        int n = notifications.sendDue(BATCH);
        if (n > 0) {
            log.info("Handled {} due notifications", n);
        }
    }

    /**
     * Once a day: one "On this day" notification per user who has something from this date in an
     * earlier year. Dedupe keys include the date, so running it more than once a day is harmless.
     * Times are UTC, like the on-this-day endpoint.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    @Transactional
    public int queueOnThisDay() {
        ZonedDateTime today = Instant.now(clock).atZone(ZoneOffset.UTC);
        Instant startOfToday = today.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        List<UUID> users = repository.usersWithOnThisDay(today.getMonthValue(), today.getDayOfMonth(), startOfToday);
        for (UUID userId : users) {
            notifications.enqueue(NotificationService.Draft.toUser(userId, NotificationCategory.ON_THIS_DAY,
                    "On this day", "You have a memory from this day in an earlier year.",
                    Map.of("screen", "on-this-day"), "on-this-day:" + userId + ":" + today.toLocalDate()));
        }
        return users.size();
    }
}
