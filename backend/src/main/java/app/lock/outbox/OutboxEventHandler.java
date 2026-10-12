package app.lock.outbox;

/**
 * Consumer of outbox events. Handlers must be idempotent: an event can be delivered more than
 * once if the dispatcher crashes after handling but before marking it processed (invariant 8).
 */
public interface OutboxEventHandler {

    boolean supports(String eventType);

    void handle(OutboxEvent event);
}
