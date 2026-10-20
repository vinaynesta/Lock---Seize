package app.lock.media.storage;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Readiness includes object storage: without it uploads, relive and integrity checks all fail, so
 * the instance should not take traffic. A HEAD on a key that never exists proves the bucket is
 * reachable and the credentials work (a missing key is a normal "empty" answer, not an error).
 */
@Component("storage")
class StorageHealthIndicator extends AbstractHealthIndicator {

    private static final String PROBE_KEY = "health/probe";

    private final ObjectStorage storage;

    StorageHealthIndicator(ObjectStorage storage) {
        super("Object storage health check failed");
        this.storage = storage;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        storage.head(PROBE_KEY);
        builder.up();
    }
}
