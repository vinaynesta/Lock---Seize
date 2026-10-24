package app.lock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Roadmap 3.2: per-plan storage quotas, enforced when an upload is initiated. */
@TestPropertySource(properties = {"lock.quota.plans.free=100KB", "lock.quota.plans.plus=1MB"})
class StorageQuotaIntegrationTest extends IntegrationTest {

    @Test
    void uploadsBeyondThePlanAreRefusedAtPresignTime() throws Exception {
        TestUser ada = register("Ada");
        String memory = createMemory(ada, personalVault(ada), "Photos");
        mvc.perform(get("/account/storage").with(ada.auth()))
                .andExpect(jsonPath("$.plan").value("free"))
                .andExpect(jsonPath("$.usedBytes").value(0))
                .andExpect(jsonPath("$.limitBytes").value(102400));

        String first = initiate(ada, memory, 60 * 1024).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // In-flight uploads count: a second presign cannot overshoot.
        initiate(ada, memory, 60 * 1024)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        mvc.perform(get("/account/storage").with(ada.auth())).andExpect(jsonPath("$.usedBytes").value(60 * 1024));

        // Removing the asset frees the space.
        mvc.perform(delete("/assets/" + read(first, "$.assetId")).with(ada.auth())).andExpect(status().isNoContent());
        initiate(ada, memory, 90 * 1024).andExpect(status().isCreated());
    }

    @Test
    void aBiggerPlanRaisesTheLimit_andUnknownPlansFallBackToTheDefault() throws Exception {
        TestUser bo = register("Bo");
        String memory = createMemory(bo, personalVault(bo), "Videos");
        jdbc.update("UPDATE users SET plan = 'plus' WHERE id = ?::uuid", bo.id().toString());
        initiate(bo, memory, 500 * 1024).andExpect(status().isCreated());
        jdbc.update("UPDATE users SET plan = 'no-such-plan' WHERE id = ?::uuid", bo.id().toString());
        mvc.perform(get("/account/storage").with(bo.auth())).andExpect(jsonPath("$.plan").value("free"));
    }

    @Test
    void failedUploadsDoNotCount() throws Exception {
        TestUser cy = register("Cy");
        String memory = createMemory(cy, personalVault(cy), "Oops");
        String asset = read(initiate(cy, memory, 80 * 1024).andReturn().getResponse().getContentAsString(), "$.assetId");
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, asset);
        storage.put(key, new byte[10]); // wrong size: verification fails
        mvc.perform(post("/assets/" + asset + "/complete").with(cy.auth()))
                .andExpect(jsonPath("$.uploadStatus").value("VERIFICATION_FAILED"));
        mvc.perform(get("/account/storage").with(cy.auth())).andExpect(jsonPath("$.usedBytes").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions initiate(TestUser user, String memory, int size) throws Exception {
        return mvc.perform(json(post("/memories/" + memory + "/assets/upload"), """
                {"type":"PHOTO","mimeType":"image/jpeg","sizeBytes":%d,"checksumSha256":"%s"}"""
                .formatted(size, InMemoryObjectStorage.sha256Base64(new byte[size]))).with(user.auth()));
    }
}
