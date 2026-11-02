package app.lock.memory;

import app.lock.identity.PasswordResetService;
import app.lock.identity.RefreshTokenService;
import app.lock.media.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic background work. Safe on several instances: each job claims its batch with
 * {@code FOR UPDATE SKIP LOCKED}, so instances split the work instead of repeating it.
 */
@Component
class LifecycleJobs {

    private static final Logger log = LoggerFactory.getLogger(LifecycleJobs.class);
    private static final int BATCH = 200;

    private final MemoryService memories;
    private final MediaService media;
    private final RefreshTokenService refreshTokens;
    private final PasswordResetService resets;

    LifecycleJobs(MemoryService memories, MediaService media, RefreshTokenService refreshTokens, PasswordResetService resets) {
        this.memories = memories;
        this.media = media;
        this.refreshTokens = refreshTokens;
        this.resets = resets;
    }

    @Scheduled(fixedDelayString = "PT1M")
    void completeSeals() {
        int n = memories.completeDueSeals(BATCH);
        if (n > 0) {
            log.info("Locked {} memories whose cooling-off window ended", n);
        }
    }

    @Scheduled(fixedDelayString = "PT15M")
    void executeDeletions() {
        int n = memories.executeDueDeletions(BATCH);
        if (n > 0) {
            log.info("Purged {} memories after their deletion grace period", n);
        }
    }

    @Scheduled(fixedDelayString = "PT30M")
    void expireUploads() {
        int n = media.expireStaleUploads(BATCH);
        if (n > 0) {
            log.info("Expired {} abandoned uploads", n);
        }
    }

    @Scheduled(fixedDelayString = "PT6H")
    void deleteExpiredRefreshTokens() {
        int n = refreshTokens.deleteExpired();
        if (n > 0) {
            log.info("Deleted {} expired refresh tokens", n);
        }
        resets.deleteExpired();
    }
}
