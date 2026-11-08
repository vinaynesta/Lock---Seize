package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.support.IntegrationTest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Roadmap 3.6d-f: sealed media (no scan, device-made thumbnail), device-to-device approval, and search
 * with tokens computed on the device. As in SealedVaultIntegrationTest, the payloads are random bytes
 * of the right shape; real encryption is tested in the client.
 */
class SealedMediaDevicesSearchIntegrationTest extends IntegrationTest {

    static final SecureRandom RANDOM = new SecureRandom();
    static final Base64.Encoder B64 = Base64.getEncoder();

    // ---- 3.6d: sealed media -----------------------------------------------------------------------

    @Test
    void sealedMediaSkipsTheScan_isUsableAtOnce_andLetsTheMemoryLock() throws Exception {
        TestUser ana = withBundle("Ana");
        String vault = readyVault(ana);
        String memory = sealedMemory(ana, vault);
        byte[] ciphertext = random(4_000);

        String assetId = uploadRaw(ana, memory, "image/jpeg", ciphertext);
        mvc.perform(post("/assets/" + assetId + "/complete").with(ana.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.uploadStatus").value("VERIFIED"));
        assertThat(jdbc.queryForObject("SELECT encryption_metadata FROM memory_assets WHERE id = ?::uuid", String.class, assetId))
                .isEqualTo("e2e:v1");

        drainOutbox(); // nothing may try to scan or thumbnail ciphertext
        assertThat(jdbc.queryForObject("SELECT upload_status FROM memory_assets WHERE id = ?::uuid", String.class, assetId))
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_derivatives WHERE asset_id = ?::uuid", Integer.class, assetId)).isZero();

        // Device-made, device-encrypted thumbnail.
        byte[] thumb = random(900);
        String ticket = mvc.perform(json(post("/assets/" + assetId + "/sealed-thumbnail"), """
                        {"sizeBytes":%d,"checksumSha256":"%s"}""".formatted(thumb.length, InMemoryObjectStorage.sha256Base64(thumb)))
                        .with(ana.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(read(ticket, "$.uploadUrl")).contains("derived/");
        String objectKey = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
        storage.put("derived/" + objectKey + "/thumb.jpg", thumb);
        mvc.perform(json(post("/assets/" + assetId + "/sealed-thumbnail/complete"), """
                        {"width":256,"height":192}""").with(ana.auth()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/memories/" + memory).with(ana.auth()))
                .andExpect(jsonPath("$.assets[0].variants[0]").value("THUMB"));
        mvc.perform(get("/assets/" + assetId + "/download").param("variant", "thumb").with(ana.auth()))
                .andExpect(jsonPath("$.variant").value("THUMB"));

        lockNow(ana, memory);
        mvc.perform(get("/memories/" + memory + "/integrity").with(ana.auth())).andExpect(jsonPath("$.intact").value(true));
    }

    @Test
    void sizeLimitsApplyToThePlaintext_andServerVaultsGetNoSealedThumbnails() throws Exception {
        TestUser bo = withBundle("Bo");
        String vault = readyVault(bo);
        String memory = sealedMemory(bo, vault);
        // 50 MB photo limit: a ciphertext of exactly 50 MB + overhead is a 50 MB photo, so it's allowed...
        long limit = 52_428_800L;
        long chunks = (limit + (1 << 20) - 1) / (1 << 20);
        long okSize = limit + 16 + chunks * 16;
        mvc.perform(json(post("/memories/" + memory + "/assets/upload"), uploadJson("image/jpeg", okSize)).with(bo.auth()))
                .andExpect(status().isCreated());
        // ...but one byte more of plaintext is not.
        mvc.perform(json(post("/memories/" + memory + "/assets/upload"), uploadJson("image/jpeg", okSize + 17)).with(bo.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIZE"));

        // A server-side asset gets its thumbnail from the server, never from a client.
        String plain = createMemory(bo, personalVault(bo), "Plain");
        String plainAsset = upload(bo, plain, "image/jpeg", MediaProcessingIntegrationTest.jpeg(30, 30));
        mvc.perform(json(post("/assets/" + plainAsset + "/sealed-thumbnail"), """
                        {"sizeBytes":10,"checksumSha256":"%s"}""".formatted(B64.encodeToString(new byte[32]))).with(bo.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));
    }

    // ---- 3.6e: device approval ---------------------------------------------------------------------

    @Test
    void newDeviceRequests_unlockedDeviceApproves_newDeviceCollectsOnce() throws Exception {
        TestUser cy = withBundle("Cy");
        byte[] requestKey = random(32);
        String req = mvc.perform(json(post("/account/device-approvals"), """
                        {"requestKey":"%s","deviceName":"Cy's tablet"}""".formatted(B64.encodeToString(requestKey))).with(cy.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fingerprint").isString())
                .andExpect(jsonPath("$.approved").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = read(req, "$.id");

        mvc.perform(post("/account/device-approvals/" + id + "/collect").with(cy.auth())).andExpect(status().isAccepted());
        mvc.perform(get("/account/device-approvals").with(cy.auth()))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].deviceName").value("Cy's tablet"))
                .andExpect(jsonPath("$[0].fingerprint").value(read(req, "$.fingerprint")));

        String sealed = sealedSecret();
        mvc.perform(json(post("/account/device-approvals/" + id + "/approve"), """
                        {"sealedSecret":"%s"}""".formatted(sealed)).with(cy.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(true));
        mvc.perform(json(post("/account/device-approvals/" + id + "/approve"), """
                        {"sealedSecret":"%s"}""".formatted(sealedSecret())).with(cy.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_APPROVED"));

        mvc.perform(post("/account/device-approvals/" + id + "/collect").with(cy.auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sealedSecret").value(sealed));
        // One-time: gone after collection.
        mvc.perform(post("/account/device-approvals/" + id + "/collect").with(cy.auth())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = 'DEVICE_APPROVED' AND actor_id = ?",
                Integer.class, cy.id())).isEqualTo(1);
    }

    @Test
    void approvalsAreScopedToTheAccount_expire_andAreRateLimited() throws Exception {
        TestUser dee = withBundle("Dee");
        TestUser eve = withBundle("Eve");
        String id = requestApproval(dee);

        // Another account can't see, approve or collect it (404, as for vaults).
        mvc.perform(get("/account/device-approvals").with(eve.auth())).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(json(post("/account/device-approvals/" + id + "/approve"), """
                        {"sealedSecret":"%s"}""".formatted(sealedSecret())).with(eve.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/account/device-approvals/" + id + "/collect").with(eve.auth())).andExpect(status().isNotFound());
        // Malformed sealed key.
        mvc.perform(json(post("/account/device-approvals/" + id + "/approve"), """
                        {"sealedSecret":"%s"}""".formatted(B64.encodeToString(random(40)))).with(dee.auth()))
                .andExpect(status().isBadRequest());

        clock.advance(Duration.ofMinutes(11));
        mvc.perform(json(post("/account/device-approvals/" + id + "/approve"), """
                        {"sealedSecret":"%s"}""".formatted(sealedSecret())).with(dee.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVAL_EXPIRED"));

        for (int i = 0; i < 5; i++) {
            requestApproval(dee);
        }
        mvc.perform(json(post("/account/device-approvals"), """
                        {"requestKey":"%s"}""".formatted(B64.encodeToString(random(32)))).with(dee.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // Cancel frees a slot.
        String last = read(mvc.perform(get("/account/device-approvals").with(dee.auth())).andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(delete("/account/device-approvals/" + last).with(dee.auth())).andExpect(status().isNoContent());
        requestApproval(dee);

        TestUser fin = register("Fin"); // no keys: nothing to approve
        mvc.perform(json(post("/account/device-approvals"), """
                        {"requestKey":"%s"}""".formatted(B64.encodeToString(random(32)))).with(fin.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_BUNDLE_REQUIRED"));
    }

    // ---- 3.6f: on-device search ----------------------------------------------------------------------

    @Test
    void searchMatchesDeviceTokens_everyWordMustMatch_andRespectsDraftPrivacy() throws Exception {
        TestUser gus = withBundle("Gus");
        TestUser hal = withBundle("Hal");
        String vault = readyVault(gus);
        invite(gus, vault, hal);
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(hal)).with(gus.auth())).andExpect(status().isOk());

        String beach = token(), trip = token(), other = token();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        createWithTokens(gus, vault, a, List.of(beach, trip));
        createWithTokens(gus, vault, b, List.of(beach));
        lockNow(gus, a.toString());

        assertThat(searchIds(gus, vault, List.of(List.of(beach)))).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(searchIds(gus, vault, List.of(List.of(beach), List.of(trip)))).containsExactly(a.toString());
        assertThat(searchIds(gus, vault, List.of(List.of(other)))).isEmpty();
        // Hal sees the locked one but not Gus's draft.
        assertThat(searchIds(hal, vault, List.of(List.of(beach)))).containsExactly(a.toString());
        // Non-members get 404; malformed tokens 400.
        TestUser ivy = withBundle("Ivy");
        mvc.perform(json(post("/vaults/" + vault + "/search"), searchJson(List.of(List.of(beach)))).with(ivy.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/vaults/" + vault + "/search"), searchJson(List.of(List.of(B64.encodeToString(random(8))))))
                        .with(gus.auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theCreatorCanReplaceTokens_evenAfterLocking_butNobodyElse() throws Exception {
        TestUser jon = withBundle("Jon");
        TestUser kim = withBundle("Kim");
        String vault = readyVault(jon);
        invite(jon, vault, kim);
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(kim)).with(jon.auth())).andExpect(status().isOk());
        UUID id = UUID.randomUUID();
        createWithTokens(jon, vault, id, List.of());
        lockNow(jon, id.toString());

        String word = token();
        mvc.perform(json(put("/memories/" + id + "/search-tokens"), tokensJson(List.of(word))).with(kim.auth()))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/memories/" + id + "/search-tokens"), tokensJson(List.of(word))).with(jon.auth()))
                .andExpect(status().isNoContent());
        assertThat(searchIds(jon, vault, List.of(List.of(word)))).containsExactly(id.toString());
        // The server vault keeps its own indexing.
        String plain = createMemory(jon, personalVault(jon), "Plain");
        mvc.perform(json(put("/memories/" + plain + "/search-tokens"), tokensJson(List.of(word))).with(jon.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));
    }

    @Test
    void devicesReindexTheirOwnOlderMemories_untilEachHasTokensOrIsMarkedEmpty() throws Exception {
        TestUser lea = withBundle("Lea");
        TestUser max = withBundle("Max");
        String vault = readyVault(lea);
        invite(lea, vault, max);
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(max)).with(lea.auth())).andExpect(status().isOk());
        String older = sealedMemory(lea, vault);   // written before 3.6f: no tokens
        String empty = sealedMemory(lea, vault);   // no words at all
        UUID indexed = UUID.randomUUID();
        createWithTokens(lea, vault, indexed, List.of(token()));
        sealedMemory(max, vault);                  // someone else's: not Lea's to index

        assertThat(unindexed(lea, vault)).containsExactlyInAnyOrder(older, empty);
        mvc.perform(json(put("/memories/" + older + "/search-tokens"), tokensJson(List.of(token()))).with(lea.auth()))
                .andExpect(status().isNoContent());
        mvc.perform(json(put("/memories/" + empty + "/search-tokens"), tokensJson(List.of())).with(lea.auth()))
                .andExpect(status().isNoContent());
        assertThat(unindexed(lea, vault)).isEmpty();
        assertThat(unindexed(max, vault)).hasSize(1);

        // Server vaults index themselves; non-members get 404.
        mvc.perform(get("/vaults/" + personalVault(lea) + "/search/unindexed").with(lea.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));
        TestUser out = withBundle("Out");
        mvc.perform(get("/vaults/" + vault + "/search/unindexed").with(out.auth())).andExpect(status().isNotFound());
    }

    private List<String> unindexed(TestUser user, String vault) throws Exception {
        String body = mvc.perform(get("/vaults/" + vault + "/search/unindexed").with(user.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$[*]");
    }

    @Test
    void sealedUploadsMayChooseTheirAssetId_serverVaultsMayNot() throws Exception {
        TestUser pam = withBundle("Pam");
        String vault = readyVault(pam);
        String memory = sealedMemory(pam, vault);
        UUID assetId = UUID.randomUUID();
        // Security review F6: the device binds the file key to the asset id, so it picks the id first.
        String body = uploadJson("image/jpeg", 2048).replace("{", "{\"id\":\"" + assetId + "\",");
        mvc.perform(json(post("/memories/" + memory + "/assets/upload"), body).with(pam.auth()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assetId").value(assetId.toString()));
        // Retrying the same unfinished upload (queued offline) gets a fresh URL for the same asset...
        mvc.perform(json(post("/memories/" + memory + "/assets/upload"), body).with(pam.auth()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assetId").value(assetId.toString()));
        // ...but a different file can't take an existing id.
        mvc.perform(json(post("/memories/" + memory + "/assets/upload"), uploadJson("image/jpeg", 4096)
                        .replace("{", "{\"id\":\"" + assetId + "\",")).with(pam.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_assets WHERE id = ?::uuid", Integer.class,
                assetId.toString())).isEqualTo(1);

        String plain = createMemory(pam, personalVault(pam), "Plain");
        mvc.perform(json(post("/memories/" + plain + "/assets/upload"),
                        uploadJson("image/jpeg", 2048).replace("{", "{\"id\":\"" + UUID.randomUUID() + "\",")).with(pam.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private List<String> searchIds(TestUser user, String vault, List<List<String>> words) throws Exception {
        String body = mvc.perform(json(post("/vaults/" + vault + "/search"), searchJson(words)).with(user.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$[*].id");
    }

    private void createWithTokens(TestUser user, String vault, UUID id, List<String> tokens) throws Exception {
        mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"id":"%s","sealed":{"keyVersion":1,"title":"%s","searchTokens":%s}}"""
                        .formatted(id, ct(10), jsonList(tokens))).with(user.auth()))
                .andExpect(status().isCreated());
    }

    private static String searchJson(List<List<String>> words) {
        List<String> parts = new ArrayList<>();
        words.forEach(w -> parts.add(jsonList(w)));
        return "{\"words\":[" + String.join(",", parts) + "]}";
    }

    private static String tokensJson(List<String> tokens) {
        return "{\"tokens\":" + jsonList(tokens) + "}";
    }

    private static String jsonList(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").reduce((x, y) -> x + "," + y).map(s -> "[" + s + "]").orElse("[]");
    }

    private String requestApproval(TestUser user) throws Exception {
        return read(mvc.perform(json(post("/account/device-approvals"), """
                        {"requestKey":"%s"}""".formatted(B64.encodeToString(random(32)))).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String uploadRaw(TestUser user, String memoryId, String mime, byte[] bytes) throws Exception {
        String ticket = mvc.perform(json(post("/memories/" + memoryId + "/assets/upload"), """
                        {"type":"PHOTO","mimeType":"%s","sizeBytes":%d,"checksumSha256":"%s"}"""
                        .formatted(mime, bytes.length, InMemoryObjectStorage.sha256Base64(bytes))).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String assetId = read(ticket, "$.assetId");
        storage.put(jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId), bytes);
        return assetId;
    }

    private static String uploadJson(String mime, long size) {
        return """
                {"type":"PHOTO","mimeType":"%s","sizeBytes":%d,"checksumSha256":"%s"}"""
                .formatted(mime, size, B64.encodeToString(new byte[32]));
    }

    private String sealedMemory(TestUser user, String vault) throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"id":"%s","sealed":{"keyVersion":1,"title":"%s"}}""".formatted(id, ct(8))).with(user.auth()))
                .andExpect(status().isCreated());
        return id.toString();
    }

    private TestUser withBundle(String name) throws Exception {
        TestUser user = register(name);
        byte[] w1 = random(61), w2 = random(61);
        w1[0] = 1;
        w2[0] = 1;
        mvc.perform(json(post("/account/key-bundle"), """
                        {"password":"%s","publicKey":"%s","wrappedByPassphrase":"%s",
                         "passphraseKdf":{"alg":"argon2id","salt":"%s","m":19456,"t":2,"p":1},
                         "wrappedByRecovery":"%s","recoveryKdf":{"alg":"hkdf-sha256","salt":"%s"}}"""
                        .formatted(user.password(), B64.encodeToString(random(32)), B64.encodeToString(w1),
                                B64.encodeToString(random(16)), B64.encodeToString(w2), B64.encodeToString(random(16))))
                        .with(user.auth()))
                .andExpect(status().isCreated());
        return user;
    }

    private String readyVault(TestUser owner) throws Exception {
        String vault = read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Sealed","encryption":"SEALED"}""").with(owner.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(owner)).with(owner.auth())).andExpect(status().isOk());
        return vault;
    }

    private void invite(TestUser owner, String vault, TestUser member) throws Exception {
        mvc.perform(json(post("/vaults/" + vault + "/members"), """
                        {"email":"%s"}""".formatted(member.email())).with(owner.auth()))
                .andExpect(status().isCreated());
        mvc.perform(post("/invitations/" + vault + "/accept").with(member.auth())).andExpect(status().isOk());
    }

    private static String keysJson(TestUser... recipients) {
        List<String> parts = new ArrayList<>();
        for (TestUser r : recipients) {
            byte[] wrapped = random(93);
            wrapped[0] = 2; // version-2 (authenticated) wrap
            parts.add("\"" + r.id() + "\":\"" + B64.encodeToString(wrapped) + "\"");
        }
        return "{\"keys\":{" + String.join(",", parts) + "}}";
    }

    private static String sealedSecret() {
        byte[] b = random(93);
        b[0] = 1;
        return B64.encodeToString(b);
    }

    private static String token() {
        return B64.encodeToString(random(16));
    }

    private static String ct(int n) {
        return B64.encodeToString(random(12 + n + 16));
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
