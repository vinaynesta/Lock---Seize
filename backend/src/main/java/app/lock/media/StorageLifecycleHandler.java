package app.lock.media;

import app.lock.config.LockProperties;
import app.lock.media.storage.ObjectStorage;
import app.lock.outbox.OutboxEvent;
import app.lock.outbox.OutboxEventHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Keeps object storage in step with the database, asynchronously and idempotently:
 * <ul>
 *   <li>memory locked → apply object-lock retention to its assets</li>
 *   <li>draft discarded, asset removed/expired/failed, memory purged → delete the bytes</li>
 * </ul>
 */
@Component
class StorageLifecycleHandler implements OutboxEventHandler {

    private static final Set<String> SINGLE_DELETE = Set.of("asset.removed", "asset.expired", "asset.verification_failed");
    private static final Set<String> BULK_DELETE = Set.of("memory.draft.discarded", "memory.purged");

    private final ObjectStorage storage;
    private final LockProperties props;
    private final Clock clock;

    StorageLifecycleHandler(ObjectStorage storage, LockProperties props, Clock clock) {
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.equals("memory.locked") || SINGLE_DELETE.contains(eventType) || BULK_DELETE.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        String type = event.getEventType();
        if (type.equals("memory.locked")) {
            int years = props.storage().objectLockYears();
            if (years > 0) {
                Instant until = Instant.now(clock).atZone(ZoneOffset.UTC).plusYears(years).toInstant();
                objectKeys(event).forEach(key -> storage.protect(key, until));
            }
        } else if (SINGLE_DELETE.contains(type)) {
            storage.delete((String) event.getPayload().get("objectKey"));
        } else if (BULK_DELETE.contains(type)) {
            objectKeys(event).forEach(storage::delete);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> objectKeys(OutboxEvent event) {
        Object keys = event.getPayload().get("objectKeys");
        return keys == null ? List.of() : (List<String>) keys;
    }
}
