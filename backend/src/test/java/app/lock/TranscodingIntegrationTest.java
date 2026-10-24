package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.FakeTranscoder;
import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.media.transcode.MediaTranscoder;
import app.lock.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Roadmap 2.8 (playback renditions of video and audio) and F7 (thumbnails for formats ImageIO can't
 * read), with a fake transcoder in place of ffmpeg. FfmpegTranscoderTest covers the real binary.
 */
@Import(TranscodingIntegrationTest.TranscoderConfig.class)
class TranscodingIntegrationTest extends IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TranscoderConfig {
        @Bean
        @Primary
        MediaTranscoder fakeTranscoder() {
            return new FakeTranscoder();
        }
    }

    @Test
    void videosGetAPlaybackRenditionAndThumbnails_originalUntouched() throws Exception {
        TestUser ana = register("Ana");
        String memory = createMemory(ana, personalVault(ana), "First steps");
        byte[] video = "a-video-from-a-phone".getBytes(StandardCharsets.UTF_8);
        String asset = uploadAs(ana, memory, "VIDEO", "video/quicktime", video);
        drainOutbox();

        mvc.perform(get("/memories/" + memory).with(ana.auth()))
                .andExpect(jsonPath("$.assets[0].variants.length()").value(3))
                .andExpect(jsonPath("$.assets[0].variants[2]").value("PLAYBACK"));
        String url = read(mvc.perform(get("/assets/" + asset + "/download?variant=playback").with(ana.auth()))
                .andExpect(jsonPath("$.variant").value("PLAYBACK"))
                .andReturn().getResponse().getContentAsString(), "$.url");
        assertThat(url).contains("derived/").endsWith("playback.mp4");
        mvc.perform(get("/assets/" + asset + "/download?variant=thumb").with(ana.auth()))
                .andExpect(jsonPath("$.variant").value("THUMB"));

        // Locking and the proof of preservation only look at the original.
        lockNow(ana, memory);
        mvc.perform(get("/memories/" + memory + "/integrity").with(ana.auth())).andExpect(jsonPath("$.intact").value(true));
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, asset);
        assertThat(storage.get(key)).contains(video);
    }

    @Test
    void audioGetsAPlaybackRenditionOnly() throws Exception {
        TestUser bo = register("Bo");
        String memory = createMemory(bo, personalVault(bo), "Voice note");
        uploadAs(bo, memory, "AUDIO", "audio/wav", "RIFF....WAVE".getBytes(StandardCharsets.UTF_8));
        drainOutbox();
        mvc.perform(get("/memories/" + memory).with(bo.auth()))
                .andExpect(jsonPath("$.assets[0].variants.length()").value(1))
                .andExpect(jsonPath("$.assets[0].variants[0]").value("PLAYBACK"));
    }

    @Test
    void heicAndWebpPhotosGetThumbnailsThroughTheTranscoder() throws Exception {
        TestUser cy = register("Cy");
        String memory = createMemory(cy, personalVault(cy), "Sunset");
        uploadAs(cy, memory, "PHOTO", "image/heic", "ftypheic-not-really".getBytes(StandardCharsets.UTF_8));
        uploadAs(cy, memory, "PHOTO", "image/webp", "RIFF....WEBP".getBytes(StandardCharsets.UTF_8));
        drainOutbox();
        mvc.perform(get("/memories/" + memory).with(cy.auth()))
                .andExpect(jsonPath("$.assets[0].variants.length()").value(2))
                .andExpect(jsonPath("$.assets[1].variants.length()").value(2));
        Integer previewWidth = jdbc.queryForObject("""
                SELECT max(greatest(d.width, d.height)) FROM asset_derivatives d JOIN memory_assets a ON a.id = d.asset_id
                WHERE a.memory_id = ?::uuid AND d.variant = 'PREVIEW'""", Integer.class, memory);
        assertThat(previewWidth).isEqualTo(1024);
    }

    @Test
    void aFileTheTranscoderCannotReadFallsBackToTheOriginal() throws Exception {
        TestUser di = register("Di");
        String memory = createMemory(di, personalVault(di), "Broken clip");
        String asset = uploadAs(di, memory, "VIDEO", "video/mp4", "CORRUPT-video".getBytes(StandardCharsets.UTF_8));
        drainOutbox();
        // No rendition, but the asset is usable and the memory can still be locked.
        mvc.perform(get("/memories/" + memory).with(di.auth()))
                .andExpect(jsonPath("$.assets[0].uploadStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.assets[0].variants.length()").value(0));
        mvc.perform(get("/assets/" + asset + "/download?variant=playback").with(di.auth()))
                .andExpect(jsonPath("$.variant").doesNotExist());
        lockNow(di, memory); // completes the seal, so no SEALING memory leaks into other tests' seal jobs
    }

    @Test
    void discardingADraftDeletesRenditionsToo() throws Exception {
        TestUser ed = register("Ed");
        String memory = createMemory(ed, personalVault(ed), "Never mind");
        String asset = uploadAs(ed, memory, "VIDEO", "video/mp4", "short-clip".getBytes(StandardCharsets.UTF_8));
        drainOutbox();
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, asset);
        assertThat(storage.exists("derived/" + key + "/playback.mp4")).isTrue();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/memories/" + memory + "/draft")
                .with(ed.auth())).andExpect(status().isNoContent());
        drainOutbox();
        assertThat(storage.exists("derived/" + key + "/playback.mp4")).isFalse();
        assertThat(storage.exists("derived/" + key + "/thumb.jpg")).isFalse();
    }

    private String uploadAs(TestUser user, String memoryId, String type, String mimeType, byte[] bytes) throws Exception {
        String body = """
                {"type":"%s","mimeType":"%s","sizeBytes":%d,"checksumSha256":"%s"}"""
                .formatted(type, mimeType, bytes.length, InMemoryObjectStorage.sha256Base64(bytes));
        String assetId = read(mvc.perform(json(post("/memories/" + memoryId + "/assets/upload"), body).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.assetId");
        storage.put(jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId), bytes);
        mvc.perform(post("/assets/" + assetId + "/complete").with(user.auth())).andExpect(status().isOk());
        return assetId;
    }
}
