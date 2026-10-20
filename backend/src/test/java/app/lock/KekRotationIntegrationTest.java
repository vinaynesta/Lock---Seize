package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.crypto.AesGcm;
import app.lock.memory.MemoryService;
import app.lock.support.IntegrationTest;
import app.lock.vault.VaultKeyRewrapper;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Roadmap 1.4: rotating the KEK re-wraps vault keys only. Simulated by giving every vault a key
 * id that is not the current one, as if it had been wrapped by a retired master key.
 */
class KekRotationIntegrationTest extends IntegrationTest {

    /** A throwaway retired key, generated per run so no key material is committed. */
    static final String RETIRED = Base64.getEncoder().encodeToString(AesGcm.newKey());

    @DynamicPropertySource
    static void retiredKey(DynamicPropertyRegistry registry) {
        registry.add("lock.crypto.retired-master-keys", () -> RETIRED);
    }

    @Autowired
    VaultKeyRewrapper rewrapper;

    @Autowired
    MemoryService memoryService;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry registry;

    @Test
    void rotationRewrapsVaultKeysAndLeavesLockedContentUntouched() throws Exception {
        TestUser nia = register("Nia");
        String vaultId = personalVault(nia);
        String memoryId = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Survives rotation"}""").with(nia.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/memories/" + memoryId + "/lock").with(nia.auth())).andExpect(status().isOk());
        clock.advance(Duration.ofHours(25));
        memoryService.completeDueSeals(100);

        String currentKek = jdbc.queryForObject("SELECT kek_id FROM vaults WHERE id = ?::uuid", String.class, vaultId);
        assertThat(currentKek).startsWith("local:");
        byte[] titleBefore = jdbc.queryForObject("SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, memoryId);
        byte[] keyBefore = jdbc.queryForObject("SELECT wrapped_key FROM vaults WHERE id = ?::uuid", byte[].class, vaultId);

        // Pretend the vault predates the current KEK (legacy row with no kek id).
        jdbc.update("UPDATE vaults SET kek_id = NULL");
        assertThat(registry.get("lock.vaults.kek.stale").gauge().value()).isGreaterThanOrEqualTo(1);
        while (rewrapper.rewrapBatch(50) > 0) { }

        assertThat(registry.get("lock.vaults.kek.stale").gauge().value()).isZero();
        assertThat(jdbc.queryForObject("SELECT kek_id FROM vaults WHERE id = ?::uuid", String.class, vaultId)).isEqualTo(currentKek);
        // The wrapped key bytes changed (fresh nonce), but memory ciphertext did not: no re-encryption,
        // so the immutability triggers and the integrity proof are unaffected.
        assertThat(jdbc.queryForObject("SELECT wrapped_key FROM vaults WHERE id = ?::uuid", byte[].class, vaultId)).isNotEqualTo(keyBefore);
        assertThat(jdbc.queryForObject("SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, memoryId)).isEqualTo(titleBefore);
        mvc.perform(get("/memories/" + memoryId).with(nia.auth())).andExpect(jsonPath("$.title").value("Survives rotation"));
        mvc.perform(get("/memories/" + memoryId + "/integrity").with(nia.auth())).andExpect(jsonPath("$.intact").value(true));
    }
}
