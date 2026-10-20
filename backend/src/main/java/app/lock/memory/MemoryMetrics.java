package app.lock.memory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Business metrics for the memory lifecycle (design doc §38). Tags never carry user, vault or
 * memory ids: they would leak who is active and explode metric cardinality.
 */
@Component
class MemoryMetrics {

    private final Counter locked;
    private final Counter purged;
    private final Counter integrityFailed;

    MemoryMetrics(MeterRegistry registry, MemoryRepository memories, Clock clock) {
        this.locked = Counter.builder("lock.memories.locked")
                .description("Memories that became permanently LOCKED").register(registry);
        this.purged = Counter.builder("lock.memories.purged")
                .description("Memories removed after their deletion grace period").register(registry);
        this.integrityFailed = Counter.builder("lock.integrity.failures")
                .description("Integrity checks that found a hash mismatch or a missing/changed object").register(registry);
        Gauge.builder("lock.seal.lag", () -> {
                    Instant now = Instant.now(clock);
                    Instant oldest = memories.oldestOverdueSeal(now);
                    return oldest == null ? 0 : Duration.between(oldest, now).toSeconds();
                })
                .baseUnit("seconds")
                .description("How long the most overdue cooling-off window has been waiting to seal").register(registry);
    }

    void memoryLocked() {
        locked.increment();
    }

    void memoryPurged() {
        purged.increment();
    }

    void integrityFailed() {
        integrityFailed.increment();
    }
}
