package app.lock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.memory.MemoryService;
import app.lock.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class VaultAndAuthIntegrationTest extends IntegrationTest {

    @Autowired
    MemoryService memoryService;

    @Test
    void registrationCreatesPersonalVaultAndLoginWorks() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(get("/vaults").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("PERSONAL"));

        mvc.perform(json(post("/auth/login"), """
                        {"email":"%s","password":"%s"}""".formatted(alice.email(), alice.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
        mvc.perform(json(post("/auth/login"), """
                        {"email":"%s","password":"nope-nope-nope"}""".formatted(alice.email())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        TestUser alice = register("Alice");
        mvc.perform(json(post("/auth/register"), """
                        {"email":"%s","password":"another-long-password","displayName":"Copy"}""".formatted(alice.email().toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/vaults")).andExpect(status().isUnauthorized());
    }

    @Test
    void webClientOriginPassesCorsPreflight() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/vaults")
                        .header("Origin", "http://localhost:8081")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "http://localhost:8081"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/vaults")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void passwordChangeKeepsMemoriesReadable() throws Exception {
        TestUser alice = register("Alice");
        String vaultId = personalVault(alice);
        String memoryId = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Still readable"}""").with(alice.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(json(post("/auth/change-password"), """
                        {"currentPassword":"%s","newPassword":"a-brand-new-password"}""".formatted(alice.password()))
                        .with(alice.auth()))
                .andExpect(status().isOk());

        mvc.perform(get("/memories/" + memoryId).with(alice.auth()))
                .andExpect(jsonPath("$.title").value("Still readable"));
    }

    @Test
    void outsidersCannotSeeVaultsOrMemories() throws Exception {
        TestUser alice = register("Alice");
        TestUser mallory = register("Mallory");
        String vaultId = personalVault(alice);
        String memoryId = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Private"}""").with(alice.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/vaults/" + vaultId).with(mallory.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/vaults/" + vaultId + "/memories").with(mallory.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/memories/" + memoryId).with(mallory.auth())).andExpect(status().isNotFound());
        mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Intrusion"}""").with(mallory.auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sharedVaultInviteAcceptCollaborateAndLeave() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");

        String vaultId = read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Our First Trip"}""").with(alice.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        // Bob has no access until he accepts.
        mvc.perform(json(post("/vaults/" + vaultId + "/members"), """
                        {"email":"%s"}""".formatted(bob.email())).with(alice.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"));
        mvc.perform(get("/vaults/" + vaultId).with(bob.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/invitations").with(bob.auth())).andExpect(jsonPath("$[0].vaultId").value(vaultId));
        mvc.perform(post("/invitations/" + vaultId + "/accept").with(bob.auth()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Members cannot invite.
        TestUser carol = register("Carol");
        mvc.perform(json(post("/vaults/" + vaultId + "/members"), """
                        {"email":"%s"}""".formatted(carol.email())).with(bob.auth()))
                .andExpect(status().isForbidden());

        // Alice's locked memory is visible to Bob; her draft is not.
        String lockedId = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Sunset in Goa"}""").with(alice.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String draftId = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"Unfinished"}""").with(alice.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/memories/" + lockedId + "/lock").with(alice.auth())).andExpect(status().isOk());
        clock.advance(Duration.ofHours(25));
        memoryService.completeDueSeals(100);

        mvc.perform(get("/memories/" + lockedId).with(bob.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Sunset in Goa"));
        mvc.perform(get("/memories/" + draftId).with(bob.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/vaults/" + vaultId + "/memories").with(bob.auth()))
                .andExpect(jsonPath("$.length()").value(1));

        // Bob cannot delete or lock Alice's memory, but can reflect on it.
        mvc.perform(json(post("/memories/" + lockedId + "/deletion-request"), """
                        {"password":"%s"}""".formatted(bob.password())).with(bob.auth()))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/memories/" + lockedId + "/reflections"), """
                        {"body":"Best trip ever"}""").with(bob.auth()))
                .andExpect(status().isCreated());

        // Breakup protocol: Bob leaves, loses access, Alice's memory is untouched.
        mvc.perform(post("/vaults/" + vaultId + "/leave").with(bob.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/memories/" + lockedId).with(bob.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/memories/" + lockedId).with(alice.auth())).andExpect(status().isOk());

        // Last member leaving makes the vault dormant, not deleted.
        mvc.perform(post("/vaults/" + vaultId + "/leave").with(alice.auth())).andExpect(status().isNoContent());
        Integer dormant = jdbc.queryForObject("SELECT count(*) FROM vaults WHERE id = ?::uuid AND state = 'DORMANT'", Integer.class, vaultId);
        org.assertj.core.api.Assertions.assertThat(dormant).isEqualTo(1);
    }

    @Test
    void ownerCanRemoveMember() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        String vaultId = read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Family"}""").with(alice.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/vaults/" + vaultId + "/members"), """
                {"email":"%s"}""".formatted(bob.email())).with(alice.auth()));
        mvc.perform(post("/invitations/" + vaultId + "/accept").with(bob.auth()));

        mvc.perform(delete("/vaults/" + vaultId + "/members/" + bob.id()).with(alice.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/vaults/" + vaultId).with(bob.auth())).andExpect(status().isNotFound());
    }

    @Test
    void personalVaultCannotHaveMembers() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        mvc.perform(json(post("/vaults/" + personalVault(alice) + "/members"), """
                        {"email":"%s"}""".formatted(bob.email())).with(alice.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERSONAL_VAULT"));
    }
}
