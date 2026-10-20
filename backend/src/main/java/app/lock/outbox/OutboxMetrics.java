package app.lock.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Outbox health as gauges (design doc §38). A growing backlog or an old oldest-event means the
 * dispatcher is stuck; any dead events mean a handler keeps failing and someone has to look.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository, Clock clock) {
        int max = OutboxDispatcher.MAX_ATTEMPTS;
        Gauge.builder("lock.outbox.pending", () -> repository.countPending(max))
                .description("Outbox events waiting to be dispatched").register(registry);
        Gauge.builder("lock.outbox.dead", () -> repository.countDead(max))
                .description("Outbox events that exhausted their retries").register(registry);
        Gauge.builder("lock.outbox.oldest.age", () -> {
                    Instant oldest = repository.oldestPending(max);
                    return oldest == null ? 0 : Duration.between(oldest, Instant.now(clock)).toSeconds();
                })
                .baseUnit("seconds")
                .description("Age of the oldest undispatched outbox event").register(registry);
    }
}
