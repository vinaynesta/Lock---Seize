package app.lock.search;

import app.lock.memory.MemoryService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Indexes memories written before search existed (roadmap 3.4). Each memory in its own transaction. */
@Component
public class SearchBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(SearchBackfillJob.class);
    private static final int BATCH = 200;

    private final SearchIndex index;
    private final MemoryService memories;

    SearchBackfillJob(SearchIndex index, MemoryService memories) {
        this.index = index;
        this.memories = memories;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT4M")
    public int run() {
        List<UUID> todo = index.unindexed(BATCH);
        int done = 0;
        for (UUID id : todo) {
            try {
                memories.reindex(id);
                done++;
            } catch (RuntimeException e) {
                log.warn("Could not index memory {}: {}", id, e.getMessage());
            }
        }
        if (done > 0) {
            log.info("Indexed {} memories for search", done);
        }
        return done;
    }
}
