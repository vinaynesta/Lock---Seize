package app.lock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Roadmap 5.1: an optional, encrypted map position per memory, frozen once the memory locks. */
class MemoryCoordinatesIntegrationTest extends IntegrationTest {

    @Test
    void coordinatesRoundTrip_areEncryptedAtRest_andValidated() throws Exception {
        TestUser ana = register("Ana");
        String vault = personalVault(ana);
        String id = read(mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"title":"Lisbon sunset","location":"Miradouro","coordinates":{"latitude":38.713889,"longitude":-9.133333}}""")
                        .with(ana.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coordinates.latitude").value(38.713889))
                .andExpect(jsonPath("$.coordinates.longitude").value(-9.133333))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Ciphertext at rest: the plaintext digits never reach the column.
        byte[] stored = jdbc.queryForObject("SELECT coordinates_ct FROM memories WHERE id = ?::uuid", byte[].class, id);
        org.assertj.core.api.Assertions.assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("38.71");

        // A draft can move or clear its pin; out-of-range values are refused.
        mvc.perform(json(patch("/memories/" + id), """
                        {"title":"Lisbon sunset","coordinates":{"latitude":91,"longitude":0}}""").with(ana.auth()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(patch("/memories/" + id), """
                        {"title":"Lisbon sunset"}""").with(ana.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.coordinates").doesNotExist());
    }

    @Test
    void lockedCoordinatesCannotChange_andTheIntegrityProofCoversThem() throws Exception {
        TestUser bo = register("Bo");
        String id = read(mvc.perform(json(post("/vaults/" + personalVault(bo) + "/memories"), """
                        {"title":"Summit","coordinates":{"latitude":46.5583,"longitude":7.8357}}""").with(bo.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        lockNow(bo, id);
        mvc.perform(get("/memories/" + id + "/integrity").with(bo.auth())).andExpect(jsonPath("$.intact").value(true));
        assertThatThrownBy(() -> jdbc.update("UPDATE memories SET coordinates_ct = NULL WHERE id = ?::uuid", id))
                .hasMessageContaining("LOCK_IMMUTABLE");
        // Memories without a pin keep their existing hash format, so older proofs stay valid.
        String plain = createMemory(bo, personalVault(bo), "No pin");
        lockNow(bo, plain);
        mvc.perform(get("/memories/" + plain + "/integrity").with(bo.auth())).andExpect(jsonPath("$.intact").value(true));
    }
}
