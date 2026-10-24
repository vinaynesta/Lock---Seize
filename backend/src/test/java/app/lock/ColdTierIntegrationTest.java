package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import app.lock.media.ColdTierJob;
import app.lock.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Roadmap 3.3: originals of long-locked memories are tagged for the cold tier; derivatives stay hot. */
@TestPropertySource(properties = {"lock.cold-tier.enabled=true", "lock.cold-tier.after=P365D"})
class ColdTierIntegrationTest extends IntegrationTest {

    @Autowired
    ColdTierJob job;

    @Test
    void oldOriginalsGoCold_derivativesAndProofAreUntouched() throws Exception {
        TestUser ada = register("Ada");
        String memory = createMemory(ada, personalVault(ada), "Summer");
        String asset = uploadPhoto(ada, memory, MediaProcessingIntegrationTest.jpeg(800, 600));
        lockNow(ada, memory);
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, asset);

        clock.advance(Duration.ofDays(200));
        drain();
        assertThat(storage.isMarkedCold(key)).isFalse();

        clock.advance(Duration.ofDays(200));
        drain();
        assertThat(storage.isMarkedCold(key)).isTrue();
        assertThat(storage.isMarkedCold("derived/" + key + "/thumb.jpg")).isFalse();
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM memory_assets WHERE id = ?::uuid", Boolean.class, asset)).isTrue();

        // Updating archived_at after locking passed the immutability trigger, and the proof still holds.
        mvc.perform(get("/memories/" + memory + "/integrity").with(ada.auth())).andExpect(jsonPath("$.intact").value(true));
        // The trigger still refuses real changes to a locked memory's asset.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE memory_assets SET size_bytes = size_bytes + 1 WHERE id = ?::uuid", asset))
                .hasMessageContaining("LOCK_IMMUTABLE");
        // Running again does nothing more.
        assertThat(job.archiveBatch()).isZero();
    }

    @Test
    void draftsAndSealedCapsulesStayHot() throws Exception {
        TestUser bo = register("Bo");
        String vault = personalVault(bo);
        String draft = createMemory(bo, vault, "Unfinished");
        String draftAsset = uploadPhoto(bo, draft, MediaProcessingIntegrationTest.jpeg(100, 100));
        String capsule = read(mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/vaults/" + vault + "/memories"), """
                        {"title":"For 2030","revealAt":"%s"}""".formatted(START.plus(Duration.ofDays(1000)))).with(bo.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String capsuleAsset = uploadPhoto(bo, capsule, MediaProcessingIntegrationTest.jpeg(100, 100));
        lockNow(bo, capsule);

        clock.advance(Duration.ofDays(500));
        drain();
        assertThat(archived(draftAsset)).isFalse();
        assertThat(archived(capsuleAsset)).isFalse(); // still sealed: its first opening should be fast
    }

    private void drain() {
        while (job.archiveBatch() > 0) {
            // drain
        }
    }

    private boolean archived(String assetId) {
        return jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM memory_assets WHERE id = ?::uuid", Boolean.class, assetId);
    }
}
