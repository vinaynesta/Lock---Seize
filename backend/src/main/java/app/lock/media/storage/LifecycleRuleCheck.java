package app.lock.media.storage;

import app.lock.config.LockProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Follow-up F6: the bucket must expire {@code exports/} shortly after the in-app expiry
 * ({@code lock.export.ttl}), as a backstop if the cleanup job misses an archive. Exports are
 * decrypted copies of a whole vault, so one left behind forever is a real exposure.
 *
 * <p>Checks the live bucket configuration at startup and every 6 hours, logs a warning when the rule
 * is missing, and exposes {@code lock.storage.exports.lifecycle}: 1 = a rule covers {@code exports/}
 * within {@link #MAX_SLACK} of the export TTL, 0 = missing or too lax, -1 = could not be read (for
 * example the credentials lack {@code s3:GetLifecycleConfiguration}). Not fatal: a broken check must
 * not stop the API, and the alert in {@code deploy/prometheus/lock-alerts.yml} pages instead.
 */
@Component
public class LifecycleRuleCheck {

    public static final String EXPORTS_PREFIX = "exports/";
    /** How much longer than the export TTL the bucket rule may keep an archive. */
    static final Duration MAX_SLACK = Duration.ofDays(3);

    private static final Logger log = LoggerFactory.getLogger(LifecycleRuleCheck.class);

    private final ObjectStorage storage;
    private final LockProperties props;
    private final AtomicInteger state = new AtomicInteger(-1);

    LifecycleRuleCheck(ObjectStorage storage, LockProperties props, MeterRegistry registry) {
        this.storage = storage;
        this.props = props;
        Gauge.builder("lock.storage.exports.lifecycle", state, AtomicInteger::get)
                .description("1 if the bucket expires exports/ in time, 0 if not, -1 if unknown").register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        check();
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT6H")
    void periodic() {
        check();
    }

    /** Runs the check, updates the gauge and returns its value. */
    public int check() {
        if (props.storage() == null || !"s3".equals(props.storage().type())) {
            state.set(1); // in-memory storage is wiped on restart; nothing to expire
            return 1;
        }
        int result;
        try {
            OptionalInt days = storage.lifecycleExpiryDays(EXPORTS_PREFIX);
            long maxDays = exportTtl().plus(MAX_SLACK).toDays();
            if (days.isEmpty()) {
                log.warn("Bucket {} has no lifecycle rule expiring {}; apply deploy/s3-lifecycle.json (roadmap F6)",
                        props.storage().bucket(), EXPORTS_PREFIX);
                result = 0;
            } else if (days.getAsInt() > maxDays) {
                log.warn("Bucket {} expires {} after {} days; it should be at most {} (roadmap F6)",
                        props.storage().bucket(), EXPORTS_PREFIX, days.getAsInt(), maxDays);
                result = 0;
            } else {
                result = 1;
            }
        } catch (RuntimeException e) {
            log.warn("Could not read the lifecycle configuration of bucket {}: {}", props.storage().bucket(), e.getMessage());
            result = -1;
        }
        state.set(result);
        return result;
    }

    private Duration exportTtl() {
        return props.export() == null || props.export().ttl() == null ? Duration.ofDays(7) : props.export().ttl();
    }
}
