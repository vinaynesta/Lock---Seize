package app.lock.media;

import app.lock.config.LockProperties;
import app.lock.media.storage.ObjectStorage;
import app.lock.memory.MemoryAsset;
import app.lock.memory.MemoryAssetRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cold-tier archival (roadmap 3.3): tags originals of long-locked memories for the bucket lifecycle
 * rule that moves them to an instant-retrieval archive class. Derivatives (thumbnails, playback
 * renditions) are never tagged, so timelines and Relive keep loading from the hot tier; only opening
 * a full-size original reads from the archive class, which with GLACIER_IR is milliseconds, not hours.
 */
@Component
public class ColdTierJob {

    private static final Logger log = LoggerFactory.getLogger(ColdTierJob.class);
    private static final int BATCH = 200;

    private final MemoryAssetRepository assets;
    private final ObjectStorage storage;
    private final LockProperties props;
    private final Clock clock;

    ColdTierJob(MemoryAssetRepository assets, ObjectStorage storage, LockProperties props, Clock clock) {
        this.assets = assets;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    void run() {
        if (props.coldTier() == null || !props.coldTier().enabled()) {
            return;
        }
        int n;
        int total = 0;
        while ((n = archiveBatch()) > 0) {
            total += n;
        }
        if (total > 0) {
            log.info("Tagged {} originals for the cold tier", total);
        }
    }

    /**
     * One batch. Tagging is idempotent, so if this transaction fails after tagging, the retry tags
     * again harmlessly. An object that cannot be tagged is skipped and logged, never fatal.
     */
    @Transactional
    public int archiveBatch() {
        Instant now = Instant.now(clock);
        List<MemoryAsset> due = assets.lockColdCandidates(now.minus(props.coldTier().after()), BATCH);
        int done = 0;
        for (MemoryAsset a : due) {
            try {
                storage.markCold(a.getObjectKey());
                a.markArchived(now);
                done++;
            } catch (RuntimeException e) {
                log.warn("Could not tag asset {} for the cold tier: {}", a.getId(), e.getMessage());
            }
        }
        return done;
    }
}
