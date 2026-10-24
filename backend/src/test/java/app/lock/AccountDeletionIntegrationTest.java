package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.account.AccountService;
import app.lock.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 2.5: account deletion under Policy C (decisions.md §14). */
class AccountDeletionIntegrationTest extends IntegrationTest {

    @Autowired
    AccountService accounts;

    @Test
    void policyC_personalPurged_sharedContributionsStay_userAnonymised() throws Exception {
        TestUser ada = register("Ada");
        TestUser bo = register("Bo");
        String personal = personalVault(ada);
        String shared = sharedVault(bo, ada);

        // Personal: a locked memory with a photo and a draft.
        String personalLocked = createMemory(ada, personal, "Only mine");
        uploadPhoto(ada, personalLocked, "personal-photo".getBytes(StandardCharsets.UTF_8));
        lockNow(ada, personalLocked);
        String personalKey = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE memory_id = ?::uuid",
                String.class, personalLocked);
        createMemory(ada, personal, "Personal draft");
        // Shared: a locked contribution, a draft, and a reflection on Bo's memory.
        String sharedLocked = createMemory(ada, shared, "Our trip");
        lockNow(ada, sharedLocked);
        String sharedDraft = createMemory(ada, shared, "Never finished");
        String bosMemory = createMemory(bo, shared, "Bo's memory");
        lockNow(bo, bosMemory);
        mvc.perform(json(post("/memories/" + bosMemory + "/reflections"), """
                        {"body":"Ada was here"}""").with(ada.auth()))
                .andExpect(status().isCreated());

        // Re-authentication is required.
        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"wrong-password"}""").with(ada.auth()))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(ada.password())).with(ada.auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Nothing happens during the grace period.
        clock.advance(Duration.ofDays(29));
        assertThat(accounts.executeNextDue()).isFalse();
        mvc.perform(get("/memories/" + personalLocked).with(ada.auth())).andExpect(status().isOk());

        clock.advance(Duration.ofDays(2));
        assertThat(accounts.executeNextDue()).isTrue();
        drainOutbox();

        // Personal vault and everything in it are gone, including the media bytes.
        assertThat(count("SELECT count(*) FROM vaults WHERE id = ?::uuid", personal)).isZero();
        assertThat(count("SELECT count(*) FROM memories WHERE vault_id = ?::uuid", personal)).isZero();
        assertThat(count("SELECT count(*) FROM vault_keys WHERE vault_id = ?::uuid", personal)).isZero();
        assertThat(storage.exists(personalKey)).isFalse();

        // Shared: the locked contribution and the reflection stay for Bo; the draft is gone.
        mvc.perform(get("/memories/" + sharedLocked).with(bo.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Our trip"))
                .andExpect(jsonPath("$.createdBy").value(ada.id().toString()));
        mvc.perform(get("/memories/" + sharedLocked + "/integrity").with(bo.auth())).andExpect(jsonPath("$.intact").value(true));
        mvc.perform(get("/memories/" + bosMemory + "/reflections").with(bo.auth()))
                .andExpect(jsonPath("$[0].body").value("Ada was here"));
        assertThat(count("SELECT count(*) FROM memories WHERE id = ?::uuid", sharedDraft)).isZero();
        assertThat(count("SELECT count(*) FROM vault_members WHERE vault_id = ?::uuid AND user_id = ?::uuid AND status = 'LEFT'",
                shared, ada.id().toString())).isEqualTo(1);
        // Ada had seen the shared key, so it was rotated.
        assertThat(count("SELECT key_version FROM vaults WHERE id = ?::uuid", shared)).isEqualTo(2);
        mvc.perform(get("/vaults/" + shared + "/members").with(bo.auth()))
                .andExpect(jsonPath("$[?(@.userId == '" + ada.id() + "')].displayName").value("Former member"));

        // The account is anonymised and cannot sign in; the email is free again.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?::uuid", String.class, ada.id())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?::uuid", String.class, ada.id())).isEqualTo("Former member");
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?::uuid", String.class, ada.id())).doesNotContain("ada");
        mvc.perform(json(post("/auth/login"), """
                        {"email":"%s","password":"%s"}""".formatted(ada.email(), ada.password())))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id = ?::uuid AND revoked_at IS NULL", ada.id().toString())).isZero();
        mvc.perform(json(post("/auth/register"), """
                        {"email":"%s","password":"another-password","displayName":"Ada again"}""".formatted(ada.email())))
                .andExpect(status().isCreated());

        // The purge is on the audit trail, which is never deleted.
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_id = ?::uuid AND event_type = 'ACCOUNT_DELETED'",
                ada.id().toString())).isEqualTo(1);
    }

    @Test
    void deletionCanBeCancelledDuringTheGracePeriod() throws Exception {
        TestUser cal = register("Cal");
        String memory = createMemory(cal, personalVault(cal), "Keep me");
        lockNow(cal, memory);

        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(cal.password())).with(cal.auth()))
                .andExpect(status().isAccepted());
        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(cal.password())).with(cal.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_PENDING"));
        mvc.perform(get("/account/deletion-request").with(cal.auth())).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(delete("/account/deletion-request").with(cal.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/account/deletion-request").with(cal.auth())).andExpect(status().isNotFound());

        clock.advance(Duration.ofDays(31));
        assertThat(accounts.executeNextDue()).isFalse();
        mvc.perform(get("/memories/" + memory).with(cal.auth())).andExpect(status().isOk());
    }

    @Test
    void lastMemberDeletingTheirAccountLeavesTheVaultDormant() throws Exception {
        TestUser dot = register("Dot");
        TestUser eli = register("Eli");
        String shared = sharedVault(dot, eli);
        String memory = createMemory(eli, shared, "Last one standing");
        lockNow(eli, memory);
        mvc.perform(post("/vaults/" + shared + "/leave").with(dot.auth())).andExpect(status().isNoContent());

        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(eli.password())).with(eli.auth()))
                .andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(31));
        assertThat(accounts.executeNextDue()).isTrue();

        assertThat(jdbc.queryForObject("SELECT state FROM vaults WHERE id = ?::uuid", String.class, shared)).isEqualTo("DORMANT");
        assertThat(count("SELECT count(*) FROM memories WHERE id = ?::uuid", memory)).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
