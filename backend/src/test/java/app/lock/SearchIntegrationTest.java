package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.search.SearchBackfillJob;
import app.lock.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 3.4: search over encrypted memories with a per-vault blind index. */
class SearchIntegrationTest extends IntegrationTest {

    @Autowired
    SearchBackfillJob backfill;

    @Test
    void findsByWordsAndPrefixes_acrossFields_caseAndAccentInsensitive() throws Exception {
        TestUser ada = register("Ada");
        String vault = personalVault(ada);
        String grad = memory(ada, vault, "Graduation day", "Finally done with Université", "Proud", "Lyon");
        String beach = memory(ada, vault, "Beach trip", "Sandcastles with Mia", null, "Nice");
        lockNow(ada, grad);

        assertThat(search(ada, vault, "gradu")).containsExactly(grad);
        assertThat(search(ada, vault, "UNIVERSITE")).containsExactly(grad);
        assertThat(search(ada, vault, "lyon proud")).containsExactly(grad); // AND across fields
        assertThat(search(ada, vault, "lyon mia")).isEmpty();
        assertThat(search(ada, vault, "mia")).containsExactly(beach);
        assertThat(search(ada, vault, "ad")).isEmpty(); // short words match whole words only
        assertThat(search(ada, vault, "   ")).isEmpty();
    }

    @Test
    void indexHoldsNoPlaintext_andIsPerVault() throws Exception {
        TestUser bo = register("Bo");
        TestUser cy = register("Cy");
        String boVault = personalVault(bo);
        String cyVault = personalVault(cy);
        memory(bo, boVault, "Wedding", null, null, null);
        memory(cy, cyVault, "Wedding", null, null, null);

        List<byte[]> tokens = jdbc.queryForList("SELECT token FROM memory_search_tokens WHERE vault_id = ?::uuid", byte[].class, boVault);
        assertThat(tokens).isNotEmpty().allSatisfy(t -> assertThat(new String(t, StandardCharsets.ISO_8859_1)).doesNotContain("wedd"));
        // Same word, different vaults: different tokens, so cross-vault frequency analysis is not possible.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM memory_search_tokens a JOIN memory_search_tokens b ON a.token = b.token
                WHERE a.vault_id = ?::uuid AND b.vault_id = ?::uuid""", Integer.class, boVault, cyVault)).isZero();
        // Non-members get 404.
        mvc.perform(get("/vaults/" + boVault + "/search").param("q", "wedding").with(cy.auth())).andExpect(status().isNotFound());
    }

    @Test
    void draftEditsReindex_draftsArePrivate_andCapsulesStaySealed() throws Exception {
        TestUser di = register("Di");
        TestUser ed = register("Ed");
        String vault = sharedVault(di, ed);
        String draft = memory(di, vault, "Surprise party", null, null, null);
        assertThat(search(ed, vault, "surprise")).isEmpty(); // someone else's draft
        assertThat(search(di, vault, "surprise")).containsExactly(draft);

        mvc.perform(json(patch("/memories/" + draft), """
                        {"title":"Birthday dinner"}""").with(di.auth())).andExpect(status().isOk());
        assertThat(search(di, vault, "surprise")).isEmpty();
        assertThat(search(di, vault, "birthday")).containsExactly(draft);

        String capsule = read(mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"title":"Secret message","revealAt":"%s"}""".formatted(START.plus(Duration.ofDays(30)))).with(di.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        lockNow(di, capsule);
        assertThat(search(ed, vault, "secret")).isEmpty();
        clock.advance(Duration.ofDays(31));
        assertThat(search(ed, vault, "secret")).containsExactly(capsule);

        // Discarding a draft removes its tokens.
        mvc.perform(delete("/memories/" + draft + "/draft").with(di.auth())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_search_tokens WHERE memory_id = ?::uuid", Integer.class, draft)).isZero();
    }

    @Test
    void keyRotationKeepsOldMemoriesSearchable_andBackfillIndexesOldMemories() throws Exception {
        TestUser fay = register("Fay");
        TestUser gil = register("Gil");
        String vault = sharedVault(fay, gil);
        String before = memory(fay, vault, "Lighthouse walk", null, null, null);
        lockNow(fay, before);
        mvc.perform(delete("/vaults/" + vault + "/members/" + gil.id()).with(fay.auth())).andExpect(status().isNoContent());
        String after = memory(fay, vault, "Lighthouse again", null, null, null);
        assertThat(search(fay, vault, "lighthouse")).containsExactlyInAnyOrder(before, after);

        // A memory from before 3.4 has no tokens; the backfill builds them from its ciphertext.
        jdbc.update("DELETE FROM memory_search_tokens WHERE memory_id = ?::uuid", before);
        assertThat(search(fay, vault, "walk")).isEmpty();
        while (backfill.run() > 0) {
            // drain
        }
        assertThat(search(fay, vault, "walk")).containsExactly(before);
    }

    private String memory(TestUser user, String vault, String title, String thought, String feeling, String location) throws Exception {
        String body = JsonPath.parse("{}").put("$", "title", title).put("$", "thought", thought)
                .put("$", "feeling", feeling).put("$", "location", location).jsonString();
        return read(mvc.perform(json(post("/vaults/" + vault + "/memories"), body).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private List<String> search(TestUser user, String vault, String q) throws Exception {
        String res = mvc.perform(get("/vaults/" + vault + "/search").param("q", q).with(user.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$[*].id");
    }
}
