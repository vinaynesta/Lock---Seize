package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.account.AccountService;
import app.lock.search.SearchBackfillJob;
import app.lock.support.IntegrationTest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Roadmap 3.6 pieces 1-3: key bundles, per-member vault keys, and device-encrypted content. The server
 * can't check the cryptography itself (it never has the keys), so these tests use random bytes of the
 * right shape and check what the server stores, refuses and hands back. Real encryption round trips
 * are tested in the client (src/lib/__tests__/sealed.test.ts).
 */
class SealedVaultIntegrationTest extends IntegrationTest {

    static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    AccountService accounts;

    @Autowired
    SearchBackfillJob searchBackfill;

    // ---- Piece 1: key bundles ----------------------------------------------------------------------

    @Test
    void keyBundleNeedsThePassword_isCreatedOnce_andNeverExposesASecret() throws Exception {
        TestUser ana = register("Ana");
        mvc.perform(get("/account/key-bundle").with(ana.auth())).andExpect(status().isNotFound());
        mvc.perform(json(post("/account/key-bundle"), bundleJson("wrong-password", publicKey())).with(ana.auth()))
                .andExpect(status().isUnauthorized());

        String pk = publicKey();
        String created = mvc.perform(json(post("/account/key-bundle"), bundleJson(ana.password(), pk)).with(ana.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicKey").value(pk))
                .andExpect(jsonPath("$.fingerprint").isString())
                .andExpect(jsonPath("$.passphraseKdf.alg").value("argon2id"))
                .andExpect(jsonPath("$.recoveryKdf.alg").value("hkdf-sha256"))
                .andReturn().getResponse().getContentAsString();
        assertThat(created).doesNotContain(ana.password());

        // A second bundle would orphan every vault key wrapped to the first: refused.
        mvc.perform(json(post("/account/key-bundle"), bundleJson(ana.password(), publicKey())).with(ana.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_BUNDLE_EXISTS"));

        // New passphrase: the key pair and the recovery wrap stay.
        String recovery = read(created, "$.wrappedByRecovery");
        String newWrap = wrappedSecret();
        mvc.perform(json(put("/account/key-bundle/passphrase"), """
                        {"password":"%s","wrappedByPassphrase":"%s","passphraseKdf":%s}"""
                        .formatted(ana.password(), newWrap, argon2Kdf())).with(ana.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrappedByPassphrase").value(newWrap))
                .andExpect(jsonPath("$.wrappedByRecovery").value(recovery))
                .andExpect(jsonPath("$.publicKey").value(pk));
    }

    @Test
    void malformedBundlesAndWeakKdfParametersAreRefused() throws Exception {
        TestUser bo = register("Bo");
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        mvc.perform(json(post("/account/key-bundle"), bundleJson(bo.password(), shortKey)).with(bo.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_E2E_PAYLOAD"));

        String weak = """
                {"alg":"argon2id","salt":"%s","m":1024,"t":1,"p":1}""".formatted(salt());
        mvc.perform(json(post("/account/key-bundle"), bundleJson(bo.password(), publicKey(), wrappedSecret(), weak, hkdfKdf()))
                        .with(bo.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_E2E_PAYLOAD"));
        // The passphrase must always be stretched: HKDF is only accepted for the 256-bit recovery phrase.
        mvc.perform(json(post("/account/key-bundle"), bundleJson(bo.password(), publicKey(), wrappedSecret(), hkdfKdf(), hkdfKdf()))
                        .with(bo.auth()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thePublicKeyCanNeverChange_evenDirectlyInTheDatabase() throws Exception {
        TestUser cy = withBundle("Cy");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE user_key_bundles SET public_key = ? WHERE user_id = ?", new byte[32], cy.id()))
                .hasMessageContaining("LOCK_IMMUTABLE");
    }

    // ---- Piece 2: per-member vault keys ----------------------------------------------------------------

    @Test
    void sealedVaultNeedsABundle_andHasNoServerKey() throws Exception {
        TestUser dee = register("Dee");
        mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Secret","encryption":"SEALED"}""").with(dee.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_BUNDLE_REQUIRED"));

        createBundle(dee);
        String vault = sealedVault(dee);
        mvc.perform(get("/vaults/" + vault).with(dee.auth()))
                .andExpect(jsonPath("$.encryption").value("SEALED"))
                .andExpect(jsonPath("$.keyVersion").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_keys WHERE vault_id = ?::uuid", Integer.class, vault)).isZero();
        // Server-side vaults are unchanged and say so.
        mvc.perform(get("/vaults/" + personalVault(dee)).with(dee.auth())).andExpect(jsonPath("$.encryption").value("SERVER"));
    }

    @Test
    void firstVersionMustReachEveryMember_thenHoldersShareItWithNewcomers() throws Exception {
        TestUser eve = withBundle("Eve");
        TestUser fin = withBundle("Fin");
        TestUser gus = withBundle("Gus");
        String vault = sealedVault(eve);
        invite(eve, vault, fin);

        mvc.perform(get("/vaults/" + vault + "/keys").with(eve.auth()))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.myKeys.length()").value(0))
                .andExpect(jsonPath("$.missingCurrent.length()").value(2))
                .andExpect(jsonPath("$.members.length()").value(2));

        // Version 1 for Eve alone would leave Fin out: the first upload must cover every active member.
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(eve)).with(eve.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERS_MISSING"));
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(eve, fin)).with(eve.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myKeys.length()").value(1))
                .andExpect(jsonPath("$.missingCurrent.length()").value(0));
        mvc.perform(get("/vaults/" + vault + "/keys").with(fin.auth())).andExpect(jsonPath("$.myKeys[0].keyVersion").value(1));
        // Nobody can overwrite a member's copy.
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(fin)).with(eve.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_EXISTS"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE vault_member_keys SET wrapped_key = ? WHERE vault_id = ?::uuid", new byte[93], vault))
                .hasMessageContaining("LOCK_IMMUTABLE");

        // Gus joins: he lacks v1 until a holder wraps it for him.
        invite(eve, vault, gus);
        mvc.perform(get("/vaults/" + vault + "/keys").with(gus.auth()))
                .andExpect(jsonPath("$.myKeys.length()").value(0))
                .andExpect(jsonPath("$.missingCurrent[0]").value(gus.id().toString()));
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(gus)).with(fin.auth())).andExpect(status().isOk());
        mvc.perform(get("/vaults/" + vault + "/keys").with(gus.auth())).andExpect(jsonPath("$.myKeys.length()").value(1));
    }

    @Test
    void onlyHoldersShare_onlyMembersReceive_nonMembersSeeNothing() throws Exception {
        TestUser hal = withBundle("Hal");
        TestUser ivy = withBundle("Ivy");
        TestUser jon = withBundle("Jon");
        String vault = sealedVault(hal);
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(hal)).with(hal.auth())).andExpect(status().isOk());

        mvc.perform(get("/vaults/" + vault + "/keys").with(jon.auth())).andExpect(status().isNotFound());
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(jon)).with(jon.auth())).andExpect(status().isNotFound());
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(jon)).with(hal.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));

        invite(hal, vault, ivy);
        // Ivy has no copy, so she can't be the one to "share" v1 with herself.
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(ivy)).with(ivy.auth())).andExpect(status().isForbidden());
        // And a server-side vault has no member keys at all.
        mvc.perform(get("/vaults/" + personalVault(hal) + "/keys").with(hal.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));
    }

    @Test
    void invitingOrJoiningASealedVaultNeedsABundle() throws Exception {
        TestUser kim = withBundle("Kim");
        TestUser lou = register("Lou");
        String vault = sealedVault(kim);
        mvc.perform(json(post("/vaults/" + vault + "/members"), """
                        {"email":"%s"}""".formatted(lou.email())).with(kim.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KEY_BUNDLE_REQUIRED"));
    }

    @Test
    void removingAMemberStartsANewVersionThatOnlyRemainingMembersGet() throws Exception {
        TestUser max = withBundle("Max");
        TestUser ned = withBundle("Ned");
        TestUser oli = withBundle("Oli");
        String vault = sealedVault(max);
        invite(max, vault, ned);
        invite(max, vault, oli);
        mvc.perform(json(post("/vaults/" + vault + "/keys/1"), keysJson(max, ned, oli)).with(max.auth())).andExpect(status().isOk());

        mvc.perform(delete("/vaults/" + vault + "/members/" + oli.id()).with(max.auth())).andExpect(status().isNoContent());
        mvc.perform(get("/vaults/" + vault + "/keys").with(ned.auth()))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.missingCurrent.length()").value(2));
        // Writing is blocked until a device creates v2...
        mvc.perform(json(post("/vaults/" + vault + "/memories"), sealedMemoryJson(UUID.randomUUID(), 2)).with(ned.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEALED_KEY_PENDING"));
        // ...which can't include the removed member, and must include everyone else.
        mvc.perform(json(post("/vaults/" + vault + "/keys/2"), keysJson(max, ned, oli)).with(ned.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_MEMBER"));
        mvc.perform(json(post("/vaults/" + vault + "/keys/2"), keysJson(max, ned)).with(ned.auth())).andExpect(status().isOk());
        // Old version 1 can no longer be created anew, only v2 is current.
        mvc.perform(json(post("/vaults/" + vault + "/memories"), sealedMemoryJson(UUID.randomUUID(), 1)).with(ned.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_KEY_VERSION"));
        mvc.perform(json(post("/vaults/" + vault + "/memories"), sealedMemoryJson(UUID.randomUUID(), 2)).with(ned.auth()))
                .andExpect(status().isCreated());
    }

    // ---- Piece 3: device-encrypted content -------------------------------------------------------------

    @Test
    void sealedMemoriesStoreAndReturnCiphertextOnly_andRefusePlaintext() throws Exception {
        TestUser pat = withBundle("Pat");
        String vault = readyVault(pat);
        UUID id = UUID.randomUUID();
        String body = sealedMemoryJson(id, 1);

        mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"title":"Plain text"}""").with(pat.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_E2E_PAYLOAD"));
        mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"id":"%s","title":"Leaked","sealed":{"keyVersion":1,"title":"%s"}}""".formatted(UUID.randomUUID(), ct(10)))
                        .with(pat.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAINTEXT_IN_SEALED_VAULT"));

        String title = read(body, "$.sealed.title");
        String created = mvc.perform(json(post("/vaults/" + vault + "/memories"), body).with(pat.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.encrypted.keyVersion").value(1))
                .andExpect(jsonPath("$.encrypted.title").value(title))
                .andReturn().getResponse().getContentAsString();
        assertThat(read(created, "$.encrypted.thought")).isNotNull();
        // Stored byte for byte; retrying the same create is idempotent.
        assertThat(Base64.getEncoder().encodeToString(jdbc.queryForObject(
                "SELECT title_ct FROM memories WHERE id = ?::uuid", byte[].class, id.toString()))).isEqualTo(title);
        mvc.perform(json(post("/vaults/" + vault + "/memories"), body).with(pat.auth()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()));
        // Server-side vaults refuse ciphertext they could never decrypt.
        mvc.perform(json(post("/vaults/" + personalVault(pat) + "/memories"), sealedMemoryJson(UUID.randomUUID(), 1)).with(pat.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOT_A_SEALED_VAULT"));

        // Edit, lock and the preservation proof work on ciphertext exactly as before.
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/memories/" + id),
                        sealedMemoryJson(null, 1)).with(pat.auth()))
                .andExpect(status().isOk());
        lockNow(pat, id.toString());
        mvc.perform(get("/memories/" + id + "/integrity").with(pat.auth())).andExpect(jsonPath("$.intact").value(true));
        mvc.perform(get("/vaults/" + vault + "/memories").with(pat.auth()))
                .andExpect(jsonPath("$[0].encrypted.title").isString())
                .andExpect(jsonPath("$[0].title").doesNotExist());
    }

    @Test
    void impossibleCiphertextLengthsAreRefused() throws Exception {
        TestUser quin = withBundle("Quin");
        String vault = readyVault(quin);
        mvc.perform(json(post("/vaults/" + vault + "/memories"), """
                        {"id":"%s","sealed":{"keyVersion":1,"title":"%s"}}""".formatted(UUID.randomUUID(), ct(0)))
                        .with(quin.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_E2E_PAYLOAD"));
    }

    @Test
    void sealedReflectionsAndClosedCapsulesRevealNothing() throws Exception {
        TestUser ray = withBundle("Ray");
        String vault = readyVault(ray);
        UUID id = UUID.randomUUID();
        mvc.perform(json(post("/vaults/" + vault + "/memories"), sealedMemoryJson(id, 1)).with(ray.auth())).andExpect(status().isCreated());
        lockNow(ray, id.toString());

        UUID reflectionId = UUID.randomUUID();
        String body = ct(30);
        mvc.perform(json(post("/memories/" + id + "/reflections"), """
                        {"body":"plaintext"}""").with(ray.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAINTEXT_IN_SEALED_VAULT"));
        mvc.perform(json(post("/memories/" + id + "/reflections"), """
                        {"id":"%s","keyVersion":1,"sealedBody":"%s"}""".formatted(reflectionId, body)).with(ray.auth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sealedBody").value(body))
                .andExpect(jsonPath("$.body").doesNotExist());
        mvc.perform(get("/memories/" + id + "/reflections").with(ray.auth()))
                .andExpect(jsonPath("$[0].id").value(reflectionId.toString()))
                .andExpect(jsonPath("$[0].sealedBody").value(body));

        // A time capsule's ciphertext is withheld until it opens, as plaintext is in other vaults.
        UUID capsule = UUID.randomUUID();
        String capsuleJson = sealedMemoryJson(capsule, 1).replace("\"sealed\"",
                "\"revealAt\":\"%s\",\"sealed\"".formatted(clock.instant().plus(Duration.ofDays(400))));
        mvc.perform(json(post("/vaults/" + vault + "/memories"), capsuleJson).with(ray.auth())).andExpect(status().isCreated());
        lockNow(ray, capsule.toString());
        mvc.perform(get("/memories/" + capsule).with(ray.auth()))
                .andExpect(jsonPath("$.sealedCapsule").value(true))
                .andExpect(jsonPath("$.encrypted").doesNotExist());
    }

    @Test
    void serverSideExportStillRefuses_andTheBackfillSkipsSealedMemories() throws Exception {
        TestUser sam = withBundle("Sam");
        String vault = readyVault(sam);
        UUID id = UUID.randomUUID();
        mvc.perform(json(post("/vaults/" + vault + "/memories"), sealedMemoryJson(id, 1)).with(sam.auth())).andExpect(status().isCreated());

        // The server can't build a sealed vault's ZIP; the device does (3.6f).
        mvc.perform(post("/vaults/" + vault + "/exports").with(sam.auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNAVAILABLE_IN_SEALED_VAULT"));
        // Plaintext search can't work either; the device sends tokens instead.
        mvc.perform(get("/vaults/" + vault + "/search").param("q", "anything").with(sam.auth()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SEALED_SEARCH_NEEDS_TOKENS"));
        searchBackfill.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_index_skips WHERE memory_id = ?::uuid", Integer.class,
                id.toString())).isEqualTo(1);
    }

    @Test
    void accountDeletionRemovesTheBundleAndWrappedKeys() throws Exception {
        TestUser tia = withBundle("Tia");
        String vault = readyVault(tia);
        mvc.perform(json(post("/account/deletion-request"), """
                        {"password":"%s"}""".formatted(tia.password())).with(tia.auth()))
                .andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(31));
        assertThat(accounts.executeNextDue()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_key_bundles WHERE user_id = ?", Integer.class, tia.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_member_keys WHERE user_id = ?", Integer.class, tia.id())).isZero();
        // Policy C: a shared vault outlives its last member (DORMANT), but no key that opens it remains.
        assertThat(jdbc.queryForObject("SELECT state FROM vaults WHERE id = ?::uuid", String.class, vault)).isEqualTo("DORMANT");
    }

    // ---- Helpers --------------------------------------------------------------------------------------

    private TestUser withBundle(String name) throws Exception {
        TestUser user = register(name);
        createBundle(user);
        return user;
    }

    private void createBundle(TestUser user) throws Exception {
        mvc.perform(json(post("/account/key-bundle"), bundleJson(user.password(), publicKey())).with(user.auth()))
                .andExpect(status().isCreated());
    }

    private String sealedVault(TestUser owner) throws Exception {
        return read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Sealed","encryption":"SEALED"}""").with(owner.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A sealed vault whose owner holds key version 1. */
    private String readyVault(TestUser owner) throws Exception {
        String vault = sealedVault(owner);
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
        StringBuilder sb = new StringBuilder("{\"keys\":{");
        for (int i = 0; i < recipients.length; i++) {
            byte[] wrapped = random(93);
            wrapped[0] = 1;
            sb.append(i > 0 ? "," : "").append('"').append(recipients[i].id()).append("\":\"")
                    .append(Base64.getEncoder().encodeToString(wrapped)).append('"');
        }
        return sb.append("}}").toString();
    }

    private static String sealedMemoryJson(UUID id, int keyVersion) {
        return """
                {%s"sealed":{"keyVersion":%d,"title":"%s","thought":"%s"}}"""
                .formatted(id == null ? "" : "\"id\":\"" + id + "\",", keyVersion, ct(12), ct(40));
    }

    /** Ciphertext shape for a plaintext of {@code n} bytes: nonce(12) + n + tag(16). */
    private static String ct(int n) {
        return Base64.getEncoder().encodeToString(random(12 + n + 16));
    }

    private static String bundleJson(String password, String publicKey) {
        return bundleJson(password, publicKey, wrappedSecret(), argon2Kdf(), hkdfKdf());
    }

    private static String bundleJson(String password, String publicKey, String wrapped, String passphraseKdf, String recoveryKdf) {
        return """
                {"password":"%s","publicKey":"%s","wrappedByPassphrase":"%s","passphraseKdf":%s,
                 "wrappedByRecovery":"%s","recoveryKdf":%s}"""
                .formatted(password, publicKey, wrapped, passphraseKdf, wrappedSecret(), recoveryKdf);
    }

    private static String argon2Kdf() {
        return """
                {"alg":"argon2id","salt":"%s","m":65536,"t":3,"p":1}""".formatted(salt());
    }

    private static String hkdfKdf() {
        return """
                {"alg":"hkdf-sha256","salt":"%s"}""".formatted(salt());
    }

    private static String publicKey() {
        return Base64.getEncoder().encodeToString(random(32));
    }

    private static String wrappedSecret() {
        byte[] b = random(61);
        b[0] = 1;
        return Base64.getEncoder().encodeToString(b);
    }

    private static String salt() {
        return Base64.getEncoder().encodeToString(random(16));
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

}
