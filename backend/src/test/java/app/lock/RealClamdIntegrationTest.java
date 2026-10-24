package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import app.lock.support.IntegrationTest;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * F2: the full upload → scan flow against a real clamd. Skipped unless clamd answers PING at
 * {@code LOCK_IT_CLAMD_HOST:LOCK_IT_CLAMD_PORT} (default 127.0.0.1:3310):
 *
 * <pre>
 *   docker compose --profile scan up -d clamav     # wait for it to load the signatures
 * </pre>
 */
@TestPropertySource(properties = {
        "lock.scan.provider=clamav",
        "lock.scan.clamav-host=${LOCK_IT_CLAMD_HOST:127.0.0.1}",
        "lock.scan.clamav-port=${LOCK_IT_CLAMD_PORT:3310}",
        "lock.scan.timeout=PT20S"})
class RealClamdIntegrationTest extends IntegrationTest {

    static final String HOST = env("LOCK_IT_CLAMD_HOST", "127.0.0.1");
    static final int PORT = Integer.parseInt(env("LOCK_IT_CLAMD_PORT", "3310"));

    @BeforeAll
    static void requireClamd() {
        assumeTrue(ping(HOST, PORT), "clamd not running at " + HOST + ":" + PORT);
    }

    @Test
    void cleanFilePasses_eicarIsRejectedAndQuarantined() throws Exception {
        TestUser ana = register("Ana");
        String memory = createMemory(ana, personalVault(ana), "Real scanner");

        String clean = upload(ana, memory, "image/jpeg", MediaProcessingIntegrationTest.jpeg(200, 100));
        String infected = upload(ana, memory, "image/jpeg", MediaProcessingIntegrationTest.EICAR);
        drainOutbox();

        assertThat(status(clean)).isEqualTo("VERIFIED");
        assertThat(status(infected)).isEqualTo("REJECTED");
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, infected);
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.exists("quarantine/" + key)).isTrue();
        // The signature clamd reported is on the audit trail.
        assertThat(jdbc.queryForObject("""
                SELECT metadata->>'signature' FROM audit_events WHERE event_type = 'ASSET_REJECTED' AND metadata->>'assetId' = ?""",
                String.class, infected)).containsIgnoringCase("eicar");
        mvc.perform(get("/memories/" + memory).with(ana.auth())).andExpect(jsonPath("$.assets.length()").value(2));
    }

    private String status(String assetId) {
        return jdbc.queryForObject("SELECT upload_status FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
    }

    static boolean ping(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readNBytes(4), StandardCharsets.US_ASCII).equals("PONG");
        } catch (Exception e) {
            return false;
        }
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
