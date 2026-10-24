package app.lock.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.media.storage.InMemoryObjectStorage;
import app.lock.security.RateLimiter;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestPostgres.class, IntegrationTest.ClockConfig.class})
public abstract class IntegrationTest {

    public static final Instant START = Instant.parse("2026-06-15T10:00:00Z");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected InMemoryObjectStorage storage;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RateLimiter rateLimiter;

    @Autowired
    protected app.lock.outbox.OutboxDispatcher dispatcher;

    @Autowired
    protected app.lock.memory.MemoryService memories;

    @BeforeEach
    void resetClock() {
        clock.set(START);
        rateLimiter.clear(); // the clock is reset, so counts from earlier tests would never age out
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    /** A registered user, with a request post-processor that authenticates as them. */
    protected record TestUser(UUID id, String email, String password) {
        public RequestPostProcessor auth() {
            return jwt().jwt(j -> j.subject(id.toString()));
        }
    }

    protected TestUser register(String name) throws Exception {
        String email = name.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery";
        String body = """
                {"email":"%s","password":"%s","displayName":"%s"}""".formatted(email, password, name);
        String token = JsonPath.read(mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        UUID id = UUID.fromString(com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getSubject());
        return new TestUser(id, email, password);
    }

    protected String personalVault(TestUser user) throws Exception {
        String vaults = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/vaults").with(user.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(vaults, "$[?(@.type == 'PERSONAL')].id");
        return ids.getFirst();
    }

    /** Runs the outbox until it is empty, so asynchronous work (scans, thumbnails, exports) completes. */
    protected void drainOutbox() {
        while (dispatcher.dispatchBatch() > 0) {
            // drain
        }
    }

    /** Uploads bytes through the direct-upload flow; the asset is left PENDING_SCAN. */
    protected String upload(TestUser user, String memoryId, String mimeType, byte[] bytes) throws Exception {
        String body = """
                {"type":"PHOTO","mimeType":"%s","sizeBytes":%d,"checksumSha256":"%s"}"""
                .formatted(mimeType, bytes.length, InMemoryObjectStorage.sha256Base64(bytes));
        String ticket = mvc.perform(json(post("/memories/" + memoryId + "/assets/upload"), body).with(user.auth()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String assetId = read(ticket, "$.assetId");
        String key = jdbc.queryForObject("SELECT object_key FROM memory_assets WHERE id = ?::uuid", String.class, assetId);
        storage.put(key, bytes); // the client's direct upload
        mvc.perform(post("/assets/" + assetId + "/complete").with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.uploadStatus").value("PENDING_SCAN"));
        return assetId;
    }

    /** Uploads a photo and runs the scan, so the asset is VERIFIED (usable) when this returns. */
    protected String uploadPhoto(TestUser user, String memoryId, byte[] bytes) throws Exception {
        String assetId = upload(user, memoryId, "image/jpeg", bytes);
        drainOutbox();
        return assetId;
    }

    protected String createMemory(TestUser user, String vaultId, String title) throws Exception {
        return read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                        {"title":"%s","thought":"Nervous but excited"}""".formatted(title)).with(user.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A shared vault owned by {@code owner} with every one of {@code others} as an active member. */
    protected String sharedVault(TestUser owner, TestUser... others) throws Exception {
        String vaultId = read(mvc.perform(json(post("/vaults"), """
                        {"type":"SHARED","name":"Shared"}""").with(owner.auth()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        for (TestUser other : others) {
            mvc.perform(json(post("/vaults/" + vaultId + "/members"), """
                            {"email":"%s"}""".formatted(other.email())).with(owner.auth()))
                    .andExpect(status().isCreated());
            mvc.perform(post("/invitations/" + vaultId + "/accept").with(other.auth())).andExpect(status().isOk());
        }
        return vaultId;
    }

    /** Locks and runs the cooling-off to completion. */
    protected void lockNow(TestUser user, String memoryId) throws Exception {
        mvc.perform(post("/memories/" + memoryId + "/lock").with(user.auth())).andExpect(status().isOk());
        clock.advance(java.time.Duration.ofHours(25));
        memories.completeDueSeals(100);
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }
}
