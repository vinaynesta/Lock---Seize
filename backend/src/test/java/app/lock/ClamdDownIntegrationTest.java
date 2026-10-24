package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F2: with clamd unreachable, uploads must stay PENDING_SCAN (never treated as clean) and the memory
 * cannot lock. Points the scanner at a port nothing listens on, so it runs everywhere.
 */
class ClamdDownIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void deadClamd(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // free now, and nothing will listen on it
        }
        int dead = port;
        registry.add("lock.scan.provider", () -> "clamav");
        registry.add("lock.scan.clamav-host", () -> "127.0.0.1");
        registry.add("lock.scan.clamav-port", () -> dead);
        registry.add("lock.scan.timeout", () -> "PT2S");
    }

    @Test
    void scannerDown_assetStaysPendingAndTheMemoryCannotLock() throws Exception {
        TestUser bo = register("Bo");
        String memory = createMemory(bo, personalVault(bo), "Scanner is down");
        String asset = upload(bo, memory, "image/jpeg", MediaProcessingIntegrationTest.jpeg(50, 50));
        dispatcher.dispatchBatch(); // one attempt; drainOutbox would keep retrying the failing scan

        assertThat(jdbc.queryForObject("SELECT upload_status FROM memory_assets WHERE id = ?::uuid", String.class, asset))
                .isEqualTo("PENDING_SCAN");
        // The failed scan was recorded on the outbox event for a retry, not swallowed.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'asset.verified'
                  AND processed_at IS NULL AND attempts > 0""", Integer.class, asset)).isEqualTo(1);
        mvc.perform(post("/memories/" + memory + "/lock").with(bo.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSETS_NOT_READY"));
    }
}
