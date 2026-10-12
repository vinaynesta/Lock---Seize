package app.lock.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder consumer standing in for the notification, search-index and timeline workers.
 * Logs identifiers only, never memory content (design doc §38).
 */
@Component
class LoggingEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventHandler.class);

    @Override
    public boolean supports(String eventType) {
        return true;
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("event={} aggregate={}:{}", event.getEventType(), event.getAggregateType(), event.getAggregateId());
    }
}
