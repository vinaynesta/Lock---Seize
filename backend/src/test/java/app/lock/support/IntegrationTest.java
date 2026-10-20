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

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }
}
