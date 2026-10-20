package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.memory.MemoryService;
import app.lock.outbox.OutboxDispatcher;
import app.lock.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class MemoryLifecycleIntegrationTest extends IntegrationTest {

    @Autowired
    MemoryService memoryService;

    @Autowired
    OutboxDispatcher outbox;

    @Test
    void createLockPreserveRelive() throws Exception {
        TestUser alice = register("Alice");
        String vaultId = personalVault(alice);

        String memoryId = createMemory(alice, vaultId, "First day at work", null);
        mvc.perform(get("/memories/" + memoryId).with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First day at work"))
                .andExpect(jsonPath("$.state").value("DRAFT"));

        // Drafts are editable.
        mvc.perform(json(patch("/memories/" + memoryId), """
                        {"title":"First day at Lock","thought":"Nervous but excited","feeling":"hopeful"}""")
                        .with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First day at Lock"));

        // Attach a photo using the direct-upload flow.
        byte[] photo = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
        String assetId = uploadPhoto(alice, memoryId, photo);

        // Lock: enters the cooling-off window with a content hash.
        String sealing = mvc.perform(post("/memories/" + memoryId + "/lock").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SEALING"))
                .andReturn().getResponse().getContentAsString();
        String contentHash = read(sealing, "$.contentHash");
        assertThat(contentHash).hasSize(64);

        // Locking again is idempotent.
        mvc.perform(post("/memories/" + memoryId + "/lock").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentHash").value(contentHash));

        // Content is frozen as soon as sealing starts.
        mvc.perform(json(patch("/memories/" + memoryId), """
                        {"title":"Rewritten history"}""").with(alice.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_IMMUTABLE"));

        // Cooling-off ends → LOCKED.
        clock.advance(Duration.ofHours(25));
        assertThat(memoryService.completeDueSeals(100)).isEqualTo(1);
        mvc.perform(get("/memories/" + memoryId).with(alice.auth()))
                .andExpect(jsonPath("$.state").value("LOCKED"))
                .andExpect(jsonPath("$.assets[0].uploadStatus").value("ATTACHED"));

        // No normal delete path for a locked memory.
        mvc.perform(delete("/memories/" + memoryId + "/draft").with(alice.auth()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/assets/" + assetId).with(alice.auth()))
                .andExpect(status().isConflict());

        // Append-only reflection, then relive.
        clock.advance(Duration.ofDays(365));
        mvc.perform(json(post("/memories/" + memoryId + "/reflections"), """
                        {"body":"Reading this a year later, it all worked out."}""").with(alice.auth()))
                .andExpect(status().isCreated());
        mvc.perform(get("/memories/" + memoryId + "/relive").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.thought").value("Nervous but excited"))
                .andExpect(jsonPath("$.daysSinceLocked").value(365))
                .andExpect(jsonPath("$.reflections[0].body").value("Reading this a year later, it all worked out."));

        // Proof of preservation.
        mvc.perform(get("/memories/" + memoryId + "/integrity").with(alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.sealedHash").value(contentHash))
                .andExpect(jsonPath("$.assetsChecked").value(1));

        // Lifecycle events are audited.
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE memory_id = ?::uuid AND event_type IN ('MEMORY_SEALING','MEMORY_LOCKED','MEMORY_RELIVED')",
                Integer.class, memoryId);
        assertThat(events).isEqualTo(3);
    }

    @Test
    void cancelDuringCoolingOffReturnsToDraft() throws Exception {
        TestUser bob = register("Bob");
        String memoryId = createMemory(bob, personalVault(bob), "Written in anger", null);

        mvc.perform(post("/memories/" + memoryId + "/lock").with(bob.auth())).andExpect(status().isOk());
        clock.advance(Duration.ofHours(2));
        mvc.perform(post("/memories/" + memoryId + "/lock/cancel").with(bob.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.contentHash").doesNotExist());

        // Back to a draft, so it can be discarded entirely.
        mvc.perform(delete("/memories/" + memoryId + "/draft").with(bob.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/memories/" + memoryId).with(bob.auth())).andExpect(status().isNotFound());
    }

    @Test
    void cannotCancelAfterCoolingOffEnds() throws Exception {
        TestUser bob = register("Bob");
        String memoryId = createMemory(bob, personalVault(bob), "Too late", null);
        mvc.perform(post("/memories/" + memoryId + "/lock").with(bob.auth())).andExpect(status().isOk());
        clock.advance(Duration.ofHours(24));
        mvc.perform(post("/memories/" + memoryId + "/lock/cancel").with(bob.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COOLING_OFF_ENDED"));
    }

    @Test
    void databaseTriggersRejectTamperingEvenWhenAppIsBypassed() throws Exception {
        TestUser carol = register("Carol");
        String memoryId = lockedMemory(carol, personalVault(carol), "Untouchable", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE memories SET title_ct = '\\x00'::bytea WHERE id = ?::uuid", memoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LOCK_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("UPDATE memories SET state = 'DRAFT' WHERE id = ?::uuid", memoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LOCK_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM memories WHERE id = ?::uuid", memoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LOCK_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE memory_id = ?::uuid", memoryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void contentIsEncryptedAtRest() throws Exception {
        TestUser dave = register("Dave");
        String memoryId = createMemory(dave, personalVault(dave), "A very secret title", null);
        byte[] stored = jdbc.queryForObject("SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, memoryId);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("secret");
    }

    @Test
    void timeCapsuleStaysSealedUntilRevealDate() throws Exception {
        TestUser erin = register("Erin");
        String revealAt = START.plus(Duration.ofDays(3650)).toString();
        String memoryId = lockedMemory(erin, personalVault(erin), "For you on your 18th birthday", revealAt);

        mvc.perform(get("/memories/" + memoryId).with(erin.auth()))
                .andExpect(jsonPath("$.sealedCapsule").value(true))
                .andExpect(jsonPath("$.title").doesNotExist());
        mvc.perform(get("/memories/" + memoryId + "/relive").with(erin.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPSULE_SEALED"));

        clock.advance(Duration.ofDays(3651));
        mvc.perform(get("/memories/" + memoryId).with(erin.auth()))
                .andExpect(jsonPath("$.sealedCapsule").value(false))
                .andExpect(jsonPath("$.title").value("For you on your 18th birthday"));
    }

    @Test
    void deliberateDelayedDeletionPurgesAfterGracePeriod() throws Exception {
        TestUser frank = register("Frank");
        String memoryId = lockedMemory(frank, personalVault(frank), "To be erased", null);

        // Re-authentication is required.
        mvc.perform(json(post("/memories/" + memoryId + "/deletion-request"), """
                        {"password":"wrong-password"}""").with(frank.auth()))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/memories/" + memoryId + "/deletion-request"), """
                        {"password":"%s","reason":"GDPR erasure"}""".formatted(frank.password())).with(frank.auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Nothing happens before the grace period ends.
        clock.advance(Duration.ofDays(29));
        assertThat(memoryService.executeDueDeletions(100)).isZero();
        mvc.perform(get("/memories/" + memoryId).with(frank.auth())).andExpect(status().isOk());

        clock.advance(Duration.ofDays(2));
        assertThat(memoryService.executeDueDeletions(100)).isEqualTo(1);
        mvc.perform(get("/memories/" + memoryId).with(frank.auth())).andExpect(status().isNotFound());

        // The purge itself remains on the audit trail.
        Integer purged = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE memory_id = ?::uuid AND event_type = 'MEMORY_PURGED'", Integer.class, memoryId);
        assertThat(purged).isEqualTo(1);
    }

    @Test
    void deletionRequestCanBeCancelled() throws Exception {
        TestUser gina = register("Gina");
        String memoryId = lockedMemory(gina, personalVault(gina), "Changed my mind", null);
        mvc.perform(json(post("/memories/" + memoryId + "/deletion-request"), """
                {"password":"%s"}""".formatted(gina.password())).with(gina.auth())).andExpect(status().isAccepted());
        mvc.perform(delete("/memories/" + memoryId + "/deletion-request").with(gina.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        clock.advance(Duration.ofDays(31));
        assertThat(memoryService.executeDueDeletions(100)).isZero();
        mvc.perform(get("/memories/" + memoryId).with(gina.auth())).andExpect(status().isOk());
    }

    @Test
    void corruptedObjectFailsIntegrityCheck() throws Exception {
        TestUser hank = register("Hank");
        String vaultId = personalVault(hank);
        String memoryId = createMemory(hank, vaultId, "Fragile", null);
        uploadPhoto(hank, memoryId, "original-bytes".getBytes(StandardCharsets.UTF_8));
        lockNow(hank, memoryId);

        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE memory_id = ?::uuid", String.class, memoryId);
        storage.corrupt(key);
        mvc.perform(get("/memories/" + memoryId + "/integrity").with(hank.auth()))
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.assetFailures.length()").value(1));
    }

    @Test
    void uploadWithMismatchedChecksumFailsVerification() throws Exception {
        TestUser ivy = register("Ivy");
        String memoryId = createMemory(ivy, personalVault(ivy), "Bad upload", null);
        byte[] declared = "declared".getBytes(StandardCharsets.UTF_8);
        String ticket = mvc.perform(json(post("/memories/" + memoryId + "/assets/upload"), uploadBody(declared)).with(ivy.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String assetId = read(ticket, "$.assetId");
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
        storage.put(key, "tampered".getBytes(StandardCharsets.UTF_8));

        mvc.perform(post("/assets/" + assetId + "/complete").with(ivy.auth()))
                .andExpect(jsonPath("$.uploadStatus").value("VERIFICATION_FAILED"));
        mvc.perform(post("/memories/" + memoryId + "/lock").with(ivy.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSETS_NOT_READY"));
    }

    @Test
    void idempotencyKeyPreventsDuplicateMemories() throws Exception {
        TestUser jack = register("Jack");
        String vaultId = personalVault(jack);
        String body = """
                {"title":"Retry me"}""";
        String first = mvc.perform(json(post("/vaults/" + vaultId + "/memories"), body)
                        .header("Idempotency-Key", "abc-123").with(jack.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(json(post("/vaults/" + vaultId + "/memories"), body)
                        .header("Idempotency-Key", "abc-123").with(jack.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(read(second, "$.id")).isEqualTo(read(first, "$.id"));
    }

    @Test
    void discardedDraftMediaIsCleanedUpByOutbox() throws Exception {
        TestUser kim = register("Kim");
        String memoryId = createMemory(kim, personalVault(kim), "Throwaway", null);
        uploadPhoto(kim, memoryId, "bytes".getBytes(StandardCharsets.UTF_8));
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE memory_id = ?::uuid", String.class, memoryId);

        mvc.perform(delete("/memories/" + memoryId + "/draft").with(kim.auth())).andExpect(status().isNoContent());
        assertThat(storage.exists(key)).isTrue();
        while (outbox.dispatchBatch() > 0) {
            // drain
        }
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void timelinePagesWithCursorUntilTheEnd() throws Exception {
        TestUser finn = register("Finn");
        String vaultId = personalVault(finn);
        for (int i = 0; i < 7; i++) {
            // Pairs share an occurredAt, so a page boundary can fall between two equal timestamps.
            mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                            {"title":"Page %d","occurredAt":"%s"}""".formatted(i, START.minus(Duration.ofDays(i / 2))))
                            .with(finn.auth()))
                    .andExpect(status().isCreated());
        }

        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/vaults/" + vaultId + "/memories").param("limit", "3").with(finn.auth());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            var result = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
            seen.addAll(com.jayway.jsonpath.JsonPath.read(result.getContentAsString(), "$[*].id"));
            cursor = result.getHeader("Next-Cursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3); // 3 + 3 + 1, and no empty trailing page
        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void malformedCursorIsRejected() throws Exception {
        TestUser gus = register("Gus");
        mvc.perform(get("/vaults/" + personalVault(gus) + "/memories").param("cursor", "not-a-cursor").with(gus.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void exportIncludesEveryMemoryBeyondOnePage() throws Exception {
        TestUser erin = register("Erin");
        String vaultId = personalVault(erin);
        int total = 230; // more than one export page (200)
        for (int i = 0; i < total; i++) {
            // Pairs share an occurredAt, so the (occurredAt, id) cursor tiebreak is exercised too.
            String body = """
                    {"title":"Memory %d","occurredAt":"%s"}""".formatted(i, START.minus(Duration.ofDays(i / 2)));
            mvc.perform(json(post("/vaults/" + vaultId + "/memories"), body).with(erin.auth()))
                    .andExpect(status().isCreated());
        }

        String export = mvc.perform(get("/vaults/" + vaultId + "/export").with(erin.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<String> ids = com.jayway.jsonpath.JsonPath.read(export, "$.memories[*].memory.id");
        assertThat(ids).hasSize(total).doesNotHaveDuplicates();
    }

    // ---- helpers ----

    private String createMemory(TestUser user, String vaultId, String title, String revealAt) throws Exception {
        String body = revealAt == null
                ? """
                  {"title":"%s","thought":"Nervous but excited"}""".formatted(title)
                : """
                  {"title":"%s","revealAt":"%s"}""".formatted(title, revealAt);
        return read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), body).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String lockedMemory(TestUser user, String vaultId, String title, String revealAt) throws Exception {
        String memoryId = createMemory(user, vaultId, title, revealAt);
        lockNow(user, memoryId);
        return memoryId;
    }

    private void lockNow(TestUser user, String memoryId) throws Exception {
        mvc.perform(post("/memories/" + memoryId + "/lock").with(user.auth())).andExpect(status().isOk());
        clock.advance(Duration.ofHours(25));
        memoryService.completeDueSeals(100);
    }

    private String uploadPhoto(TestUser user, String memoryId, byte[] bytes) throws Exception {
        String ticket = mvc.perform(json(post("/memories/" + memoryId + "/assets/upload"), uploadBody(bytes)).with(user.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andReturn().getResponse().getContentAsString();
        String assetId = read(ticket, "$.assetId");
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
        storage.put(key, bytes); // the client's direct upload
        mvc.perform(post("/assets/" + assetId + "/complete").with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadStatus").value("VERIFIED"));
        return assetId;
    }

    private static String uploadBody(byte[] bytes) {
        return """
                {"type":"PHOTO","mimeType":"image/jpeg","sizeBytes":%d,"checksumSha256":"%s"}"""
                .formatted(bytes.length, InMemoryObjectStorage.sha256Base64(bytes));
    }
}
