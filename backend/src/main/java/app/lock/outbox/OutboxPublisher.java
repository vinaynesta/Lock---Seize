package app.lock.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a domain event in the same transaction as the state change (transactional outbox),
 * so downstream work (notifications, indexing, media processing) is never lost or phantom.
 */
@Service
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload, Instant.now(clock)));
    }
}
