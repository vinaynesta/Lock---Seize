package app.lock.vault;

import app.lock.crypto.ContentCipher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
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

    private final VaultRepository vaults;
    private final ContentCipher cipher;
    private final Clock clock;

    public VaultKeyRewrapper(VaultRepository vaults, ContentCipher cipher, MeterRegistry registry, Clock clock) {
        this.vaults = vaults;
        this.cipher = cipher;
        this.clock = clock;
        Gauge.builder("lock.vaults.kek.stale", () -> vaults.countNotWrappedBy(cipher.currentKekId()))
                .description("Vaults whose data key is not yet wrapped by the current KEK").register(registry);
    }

    /** Re-wraps up to {@code batchSize} vault keys. Safe on several instances (SKIP LOCKED). */
    @Transactional
    public int rewrapBatch(int batchSize) {
        List<Vault> stale = vaults.lockNotWrappedBy(cipher.currentKekId(), batchSize);
        Instant now = Instant.now(clock);
        for (Vault vault : stale) {
            vault.rewrapKey(cipher.rewrap(vault.getWrappedKey()), now);
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
