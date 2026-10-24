package app.lock.vault;

import app.lock.crypto.ContentCipher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KEK rotation (roadmap 1.4): moves every vault data key onto the current KEK, a batch at a time.
 * Only the small wrapped key changes; memory ciphertext, content hashes and integrity proofs are
 * untouched, so locked memories stay locked.
 *
 * <p>To rotate: configure the new KEK, list the old one in {@code lock.crypto.retired-master-keys},
 * deploy, wait for {@code lock.vaults.kek.stale} to reach 0, then remove the old key.
 */
@Service
public class VaultKeyRewrapper {

    private static final Logger log = LoggerFactory.getLogger(VaultKeyRewrapper.class);
    private static final int BATCH = 100;

    private final VaultKeyRepository keys;
    private final ContentCipher cipher;

    public VaultKeyRewrapper(VaultKeyRepository keys, ContentCipher cipher, MeterRegistry registry) {
        this.keys = keys;
        this.cipher = cipher;
        Gauge.builder("lock.vaults.kek.stale", () -> keys.countNotWrappedBy(cipher.currentKekId()))
                .description("Vault key versions not yet wrapped by the current KEK").register(registry);
    }

    /** Re-wraps up to {@code batchSize} vault key versions. Safe on several instances (SKIP LOCKED). */
    @Transactional
    public int rewrapBatch(int batchSize) {
        List<VaultKey> stale = keys.lockNotWrappedBy(cipher.currentKekId(), batchSize);
        for (VaultKey key : stale) {
            key.rewrap(cipher.rewrap(key.getWrappedKey()));
        }
        return stale.size();
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void scheduledRewrap() {
        int n = rewrapBatch(BATCH);
        if (n > 0) {
            log.info("Re-wrapped {} vault keys under the current key-encryption key", n);
        }
    }
}
