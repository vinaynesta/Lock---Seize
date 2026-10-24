package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Roadmap 2.4: removing a member, or a member leaving, gives the vault a new data key for new
 * content. Old content stays under its old key, untouched, so locked memories stay intact.
 */
class VaultKeyRotationIntegrationTest extends IntegrationTest {

    @Test
    void removingAMemberRotatesTheKeyForNewContentOnly() throws Exception {
        TestUser owen = register("Owen");
        TestUser mia = register("Mia");
        String vaultId = sharedVault(owen, mia);

        String before = createMemory(owen, vaultId, "Before the split");
        lockNow(owen, before);
        mvc.perform(json(post("/memories/" + before + "/reflections"), """
                        {"body":"Written under v1"}""").with(mia.auth()))
                .andExpect(status().isCreated());
        byte[] ciphertextBefore = jdbc.queryForObject("SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, before);

        mvc.perform(delete("/vaults/" + vaultId + "/members/" + mia.id()).with(owen.auth())).andExpect(status().isNoContent());

        assertThat(keyVersion(vaultId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_keys WHERE vault_id = ?::uuid", Integer.class, vaultId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE vault_id = ?::uuid AND event_type = 'VAULT_KEY_ROTATED'",
                Integer.class, vaultId)).isEqualTo(1);

        // New content uses the new key; the old memory keeps v1, byte for byte, and still reads and verifies.
        String after = createMemory(owen, vaultId, "After the split");
        assertThat(memoryKeyVersion(after)).isEqualTo(2);
        assertThat(memoryKeyVersion(before)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, before)).isEqualTo(ciphertextBefore);
        mvc.perform(get("/memories/" + before).with(owen.auth())).andExpect(jsonPath("$.title").value("Before the split"));
        mvc.perform(get("/memories/" + after).with(owen.auth())).andExpect(jsonPath("$.title").value("After the split"));
        mvc.perform(get("/memories/" + before + "/integrity").with(owen.auth())).andExpect(jsonPath("$.intact").value(true));

        // A reflection added now is encrypted with v2; both old and new reflections decrypt.
        mvc.perform(json(post("/memories/" + before + "/reflections"), """
                        {"body":"Written under v2"}""").with(owen.auth()))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForList("SELECT key_version FROM memory_reflections WHERE memory_id = ?::uuid ORDER BY created_at, key_version",
                Integer.class, before)).containsExactly(1, 2);
        mvc.perform(get("/memories/" + before + "/reflections").with(owen.auth()))
                .andExpect(jsonPath("$[0].body").value("Written under v1"))
                .andExpect(jsonPath("$[1].body").value("Written under v2"));
    }

    @Test
    void leavingRotatesButRemovingAnInviteeDoesNot() throws Exception {
        TestUser pia = register("Pia");
        TestUser raj = register("Raj");
        TestUser sam = register("Sam");
        String vaultId = sharedVault(pia, raj);

        mvc.perform(json(post("/vaults/" + vaultId + "/members"), """
                        {"email":"%s"}""".formatted(sam.email())).with(pia.auth()))
                .andExpect(status().isCreated());
        mvc.perform(delete("/vaults/" + vaultId + "/members/" + sam.id()).with(pia.auth())).andExpect(status().isNoContent());
        assertThat(keyVersion(vaultId)).isEqualTo(1); // Sam never had access

        mvc.perform(post("/vaults/" + vaultId + "/leave").with(raj.auth())).andExpect(status().isNoContent());
        assertThat(keyVersion(vaultId)).isEqualTo(2);

        // The last member leaving makes the vault dormant; nobody is left to protect from, so no rotation.
        mvc.perform(post("/vaults/" + vaultId + "/leave").with(pia.auth())).andExpect(status().isNoContent());
        assertThat(keyVersion(vaultId)).isEqualTo(2);
    }

    @Test
    void aDraftEditedAfterRotationMovesToTheNewKey() throws Exception {
        TestUser tia = register("Tia");
        TestUser uma = register("Uma");
        String vaultId = sharedVault(tia, uma);
        String draft = createMemory(tia, vaultId, "Half written");
        assertThat(memoryKeyVersion(draft)).isEqualTo(1);

        mvc.perform(post("/vaults/" + vaultId + "/leave").with(uma.auth())).andExpect(status().isNoContent());
        mvc.perform(json(patch("/memories/" + draft), """
                        {"title":"Finished"}""").with(tia.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Finished"));
        assertThat(memoryKeyVersion(draft)).isEqualTo(2);
        lockNow(tia, draft);
        mvc.perform(get("/memories/" + draft + "/integrity").with(tia.auth())).andExpect(jsonPath("$.intact").value(true));
    }

    private int keyVersion(String vaultId) {
        return jdbc.queryForObject("SELECT key_version FROM vaults WHERE id = ?::uuid", Integer.class, vaultId);
    }

    private int memoryKeyVersion(String memoryId) {
        return jdbc.queryForObject("SELECT key_version FROM memories WHERE id = ?::uuid", Integer.class, memoryId);
    }
}
