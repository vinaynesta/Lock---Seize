package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.AssetProcessor;
import app.lock.media.storage.ObjectStorage;
import app.lock.memory.AssetDerivative;
import app.lock.support.IntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 2.1 (thumbnails) and 2.2 (malware scanning). */
class MediaProcessingIntegrationTest extends IntegrationTest {

    /** EICAR anti-virus test string, assembled at runtime so this file is not flagged itself. */
    static final byte[] EICAR = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
            .getBytes(StandardCharsets.US_ASCII);

    @Autowired
    AssetProcessor processor;

    @Test
    void memoryCannotLockUntilItsMediaIsScannedClean() throws Exception {
        TestUser ana = register("Ana");
        String memoryId = createMemory(ana, personalVault(ana), "Beach day");
        String assetId = upload(ana, memoryId, "image/jpeg", jpeg(400, 300));

        // Uploaded and checksum-verified, but not scanned yet: not usable, and the memory cannot lock.
        mvc.perform(post("/memories/" + memoryId + "/lock").with(ana.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSETS_NOT_READY"));
        mvc.perform(get("/assets/" + assetId + "/download").with(ana.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_READY"));

        drainOutbox(); // scan, then thumbnails

        mvc.perform(get("/memories/" + memoryId).with(ana.auth()))
                .andExpect(jsonPath("$.assets[0].uploadStatus").value("VERIFIED"));
        mvc.perform(post("/memories/" + memoryId + "/lock").with(ana.auth())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE memory_id = ?::uuid AND event_type = 'ASSET_SCANNED'",
                Integer.class, memoryId)).isEqualTo(1);
    }

    @Test
    void infectedUploadIsRejectedAndQuarantined() throws Exception {
        TestUser ben = register("Ben");
        String memoryId = createMemory(ben, personalVault(ben), "Suspicious");
        String assetId = upload(ben, memoryId, "image/jpeg", EICAR);
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);

        drainOutbox();

        mvc.perform(get("/memories/" + memoryId).with(ben.auth()))
                .andExpect(jsonPath("$.assets[0].uploadStatus").value("REJECTED"));
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.exists(ObjectStorage.QUARANTINE_PREFIX + key)).isTrue();
        mvc.perform(post("/memories/" + memoryId + "/lock").with(ben.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSETS_NOT_READY"));
        mvc.perform(get("/assets/" + assetId + "/download").with(ben.auth())).andExpect(status().isConflict());
        String signature = jdbc.queryForObject(
                "SELECT metadata->>'signature' FROM audit_events WHERE memory_id = ?::uuid AND event_type = 'ASSET_REJECTED'",
                String.class, memoryId);
        assertThat(signature).isEqualTo("Eicar-Test-Signature");

        // The owner removes it and can then lock.
        mvc.perform(delete("/assets/" + assetId).with(ben.auth())).andExpect(status().isNoContent());
        mvc.perform(post("/memories/" + memoryId + "/lock").with(ben.auth())).andExpect(status().isOk());
    }

    @Test
    void scanningTwiceIsHarmless() throws Exception {
        TestUser cy = register("Cy");
        String memoryId = createMemory(cy, personalVault(cy), "Twice");
        String assetId = upload(cy, memoryId, "image/jpeg", "bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(processor.scan(UUID.fromString(assetId))).isTrue();
        assertThat(processor.scan(UUID.fromString(assetId))).isFalse(); // redelivered event: no-op
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE memory_id = ?::uuid AND event_type = 'ASSET_SCANNED'",
                Integer.class, memoryId)).isEqualTo(1);
    }

    @Test
    void photosGetThumbnailsAndOriginalsStayUntouched() throws Exception {
        TestUser dee = register("Dee");
        String memoryId = createMemory(dee, personalVault(dee), "Mountains");
        byte[] original = jpeg(2000, 1000);
        String assetId = uploadPhoto(dee, memoryId, original);
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);

        mvc.perform(get("/memories/" + memoryId).with(dee.auth()))
                .andExpect(jsonPath("$.assets[0].variants[0]").value("THUMB"))
                .andExpect(jsonPath("$.assets[0].variants[1]").value("PREVIEW"));

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                storage.get(AssetDerivative.keyFor(key, AssetDerivative.Variant.THUMB)).orElseThrow()));
        assertThat(thumb.getWidth()).isEqualTo(256);
        assertThat(thumb.getHeight()).isEqualTo(128);
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(
                storage.get(AssetDerivative.keyFor(key, AssetDerivative.Variant.PREVIEW)).orElseThrow()));
        assertThat(preview.getWidth()).isEqualTo(1024);
        assertThat(storage.get(key).orElseThrow()).isEqualTo(original);

        String thumbUrl = read(mvc.perform(get("/assets/" + assetId + "/download?variant=thumb").with(dee.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant").value("THUMB"))
                .andReturn().getResponse().getContentAsString(), "$.url");
        assertThat(thumbUrl).contains("derived/");
        mvc.perform(get("/assets/" + assetId + "/download").with(dee.auth()))
                .andExpect(jsonPath("$.variant").doesNotExist());

        // Derivatives are not part of the proof of preservation.
        lockNow(dee, memoryId);
        storage.corrupt(AssetDerivative.keyFor(key, AssetDerivative.Variant.THUMB));
        mvc.perform(get("/memories/" + memoryId + "/integrity").with(dee.auth()))
                .andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void unsupportedFormatsFallBackToTheOriginal() throws Exception {
        TestUser eve = register("Eve");
        String memoryId = createMemory(eve, personalVault(eve), "Not decodable");
        String assetId = uploadPhoto(eve, memoryId, "definitely-not-an-image".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/memories/" + memoryId).with(eve.auth()))
                .andExpect(jsonPath("$.assets[0].uploadStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.assets[0].variants.length()").value(0));
        mvc.perform(get("/assets/" + assetId + "/download?variant=thumb").with(eve.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variant").doesNotExist());
        mvc.perform(get("/assets/" + assetId + "/download?variant=huge").with(eve.auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void discardingADraftDeletesDerivativesToo() throws Exception {
        TestUser fay = register("Fay");
        String memoryId = createMemory(fay, personalVault(fay), "Gone");
        String assetId = uploadPhoto(fay, memoryId, jpeg(300, 300));
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
        String thumbKey = AssetDerivative.keyFor(key, AssetDerivative.Variant.THUMB);
        assertThat(storage.exists(thumbKey)).isTrue();

        mvc.perform(delete("/memories/" + memoryId + "/draft").with(fay.auth())).andExpect(status().isNoContent());
        drainOutbox();
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.exists(thumbKey)).isFalse();
    }

    static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
