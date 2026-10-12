package app.lock.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox and hands events to in-process handlers.
 *
 * <p>This is the MVP seam for the queue in the design doc: replace the handler loop with a
 * Kafka/SQS producer when workers move out of process. Producers do not change.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository repository;
    private final List<OutboxEventHandler> handlers;
    private final Clock clock;

    public OutboxDispatcher(OutboxEventRepository repository, List<OutboxEventHandler> handlers, Clock clock) {
        this.repository = repository;
        this.handlers = handlers;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5S")
    @Transactional
    public int dispatchBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(MAX_ATTEMPTS, BATCH_SIZE);
        for (OutboxEvent event : batch) {
            try {
                handlers.stream()
                        .filter(h -> h.supports(event.getEventType()))
                        .forEach(h -> h.handle(event));
                event.markProcessed(Instant.now(clock));
            } catch (RuntimeException e) {
                log.warn("Outbox event {} ({}) failed: {}", event.getId(), event.getEventType(), e.getMessage());
                event.markFailed(e.getMessage());
            }
        }
        return batch.size();
    }
}
