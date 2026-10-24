package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.export.ExportService;
import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 2.3: asynchronous ZIP export with the manifest and original media. */
class ZipExportIntegrationTest extends IntegrationTest {

    @Autowired
    ExportService exports;

    @Test
    void zipContainsManifestAndOriginalMediaWithMatchingHashes() throws Exception {
        TestUser vic = register("Vic");
        String vaultId = personalVault(vic);
        String locked = createMemory(vic, vaultId, "Graduation");
        byte[] photo = "original-photo-bytes".getBytes(StandardCharsets.UTF_8);
        String assetId = uploadPhoto(vic, locked, photo);
        lockNow(vic, locked);
        createMemory(vic, vaultId, "Still a draft");

        String exportId = read(mvc.perform(post("/vaults/" + vaultId + "/exports").with(vic.auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // One at a time per vault.
        mvc.perform(post("/vaults/" + vaultId + "/exports").with(vic.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_IN_PROGRESS"));

        drainOutbox();

        mvc.perform(get("/exports/" + exportId).with(vic.auth()))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.memoryCount").value(2))
                .andExpect(jsonPath("$.assetCount").value(1))
                .andExpect(jsonPath("$.expiresAt").value(START.plus(Duration.ofHours(25)).plus(Duration.ofDays(7)).toString()));
        String url = read(mvc.perform(get("/exports/" + exportId + "/download").with(vic.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        String key = url.substring(url.indexOf("exports/"));

        Map<String, byte[]> zip = unzip(storage.get(key).orElseThrow());
        String manifest = new String(zip.get("manifest.json"), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(manifest, "$.format")).isEqualTo("lock-export/v2");
        List<String> titles = JsonPath.read(manifest, "$.memories[*].memory.title");
        assertThat(titles).containsExactlyInAnyOrder("Graduation", "Still a draft");
        List<String> paths = JsonPath.read(manifest, "$.memories[?(@.memory.id == '" + locked + "')].files[0].path");
        List<String> checksums = JsonPath.read(manifest, "$.memories[?(@.memory.id == '" + locked + "')].files[0].checksumSha256");
        assertThat(paths.getFirst()).isEqualTo("media/" + locked + "/" + assetId + ".jpg");
        assertThat(zip.get(paths.getFirst())).isEqualTo(photo);
        assertThat(InMemoryObjectStorage.sha256Base64(zip.get(paths.getFirst()))).isEqualTo(checksums.getFirst());
        List<String> hashes = JsonPath.read(manifest, "$.memories[?(@.memory.id == '" + locked + "')].memory.contentHash");
        assertThat(hashes.getFirst()).hasSize(64);

        // A new export can be requested once this one is done.
        mvc.perform(post("/vaults/" + vaultId + "/exports").with(vic.auth())).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE vault_id = ?::uuid AND event_type = 'EXPORT_DOWNLOADED'",
                Integer.class, vaultId)).isEqualTo(1);
    }

    @Test
    void exportsSeeOnlyWhatTheRequesterCanSee() throws Exception {
        TestUser wes = register("Wes");
        TestUser xia = register("Xia");
        String vaultId = sharedVault(wes, xia);
        String wesLocked = createMemory(wes, vaultId, "Shared moment");
        lockNow(wes, wesLocked);
        createMemory(wes, vaultId, "Wes private draft");

        String exportId = read(mvc.perform(post("/vaults/" + vaultId + "/exports").with(xia.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        drainOutbox();

        // Wes cannot see or download Xia's export.
        mvc.perform(get("/exports/" + exportId).with(wes.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/exports/" + exportId + "/download").with(wes.auth())).andExpect(status().isNotFound());

        String url = read(mvc.perform(get("/exports/" + exportId + "/download").with(xia.auth()))
                .andReturn().getResponse().getContentAsString(), "$.url");
        String manifest = new String(unzip(storage.get(url.substring(url.indexOf("exports/"))).orElseThrow())
                .get("manifest.json"), StandardCharsets.UTF_8);
        List<String> titles = JsonPath.read(manifest, "$.memories[*].memory.title");
        assertThat(titles).containsExactly("Shared moment");

        // Leaving the vault takes the export with it.
        mvc.perform(post("/vaults/" + vaultId + "/leave").with(xia.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/exports/" + exportId + "/download").with(xia.auth())).andExpect(status().isNotFound());
    }

    @Test
    void archivesExpireAndAreDeleted() throws Exception {
        TestUser yan = register("Yan");
        String vaultId = personalVault(yan);
        createMemory(yan, vaultId, "Short lived");
        String exportId = read(mvc.perform(post("/vaults/" + vaultId + "/exports").with(yan.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        drainOutbox();
        String key = jdbc.queryForObject("SELECT object_key FROM vault_exports WHERE id = ?::uuid", String.class, exportId);
        assertThat(storage.exists(key)).isTrue();

        clock.advance(Duration.ofDays(8));
        assertThat(exports.expireDue(100)).isPositive(); // the shared test database may hold other tests' exports too
        assertThat(jdbc.queryForObject("SELECT status FROM vault_exports WHERE id = ?::uuid", String.class, exportId)).isEqualTo("EXPIRED");
        assertThat(storage.exists(key)).isFalse();
        mvc.perform(get("/exports/" + exportId + "/download").with(yan.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
    }

    @Test
    void nonMembersCannotExport() throws Exception {
        TestUser zed = register("Zed");
        TestUser mallory = register("Mallory");
        mvc.perform(post("/vaults/" + personalVault(zed) + "/exports").with(mallory.auth())).andExpect(status().isNotFound());
        mvc.perform(delete("/vaults/" + personalVault(zed) + "/exports").with(zed.auth())).andExpect(status().is4xxClientError());
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries.put(e.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
