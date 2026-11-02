package app.lock;

import static org.assertj.core.api.Assertions.assertThat;

import app.lock.media.storage.LifecycleRuleCheck;
import app.lock.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Follow-ups F3 and F6: the gauges the production alerts in deploy/prometheus/lock-alerts.yml rely on. */
class OpsMonitoringIntegrationTest extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    LifecycleRuleCheck lifecycle;

    @Test
    void oldestPendingScanAgeTracksTheLongestWaitingUpload() throws Exception {
        drainOutbox(); // uploads left over from other tests in this context
        assertThat(registry.get("lock.scan.oldest.age").gauge().value()).isZero();

        TestUser kai = register("Kai");
        String memory = createMemory(kai, personalVault(kai), "Waiting for the scanner");
        upload(kai, memory, "image/jpeg", MediaProcessingIntegrationTest.jpeg(20, 20)); // not scanned yet
        clock.advance(Duration.ofMinutes(40));

        assertThat(registry.get("lock.scan.oldest.age").gauge().value()).isGreaterThanOrEqualTo(40 * 60);
        assertThat(registry.get("lock.scan.pending").gauge().value()).isGreaterThanOrEqualTo(1);

        drainOutbox(); // the dev scanner clears it
        assertThat(registry.get("lock.scan.oldest.age").gauge().value()).isZero();
    }

    @Test
    void inMemoryStorageNeedsNoLifecycleRule() {
        assertThat(lifecycle.check()).isEqualTo(1);
        assertThat(registry.get("lock.storage.exports.lifecycle").gauge().value()).isEqualTo(1);
    }
}
