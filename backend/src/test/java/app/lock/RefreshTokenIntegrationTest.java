package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class RefreshTokenIntegrationTest extends IntegrationTest {

    @Test
    void loginReturnsRefreshTokenThatRotatesOnUse() throws Exception {
        TestUser alice = register("Alice");
        String first = login(alice);

        String response = refresh(first).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String second = read(response, "$.refreshToken");

        assertThat(second).isNotEqualTo(first);
        refresh(second).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeFamily() throws Exception {
        TestUser bob = register("Bob");
        String stolen = login(bob);
        String legit = read(refresh(stolen).andReturn().getResponse().getContentAsString(), "$.refreshToken");

        // The old token shows up again: someone kept a copy.
        refresh(stolen).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        // The revocation committed despite the 401, and also took out the legitimate successor.
        refresh(legit).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'REFRESH_TOKEN_REUSED' AND actor_id = ?",
                Long.class, bob.id())).isEqualTo(1);
    }

    @Test
    void otherSessionsSurviveAReuseInOneFamily() throws Exception {
        TestUser carol = register("Carol");
        String phone = login(carol);
        String laptop = login(carol);
        String phoneNext = read(refresh(phone).andReturn().getResponse().getContentAsString(), "$.refreshToken");

        refresh(phone).andExpect(status().isUnauthorized());

        refresh(phoneNext).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isOk());
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        TestUser dave = register("Dave");
        String token = login(dave);
        clock.advance(Duration.ofDays(31));
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        TestUser erin = register("Erin");
        String token = login(erin);
        mvc.perform(json(post("/auth/logout"), body(token))).andExpect(status().isNoContent());
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWorksWithAnExpiredAccessToken() throws Exception {
        TestUser hal = register("Hal");
        String token = login(hal);
        mvc.perform(json(post("/auth/logout"), body(token)).header("Authorization", "Bearer expired.or.garbage"))
                .andExpect(status().isNoContent());
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeRevokesEverySessionAndReturnsANewOne() throws Exception {
        TestUser frank = register("Frank");
        String otherDevice = login(frank);

        String response = mvc.perform(json(post("/auth/change-password"), """
                        {"currentPassword":"%s","newPassword":"a-brand-new-password"}""".formatted(frank.password()))
                        .with(frank.auth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        refresh(otherDevice).andExpect(status().isUnauthorized());
        refresh(read(response, "$.refreshToken")).andExpect(status().isOk());
    }

    @Test
    void onlyTheHashIsStored() throws Exception {
        TestUser gina = register("Gina");
        String token = login(gina);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, token))
                .isZero();
    }

    @Test
    void eachSessionRemembersItsClient() throws Exception {
        TestUser eve = register("Eve");
        String creds = """
                {"email":"%s","password":"%s"}""".formatted(eve.email(), eve.password());
        String phone = read(mvc.perform(json(post("/auth/login"), creds).header("User-Agent", "Lock/1.0 (iPhone; iOS 19)"))
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");
        // A rotation without a header keeps the session's client.
        refresh(phone).andExpect(status().isOk());
        // Long values are truncated.
        mvc.perform(json(post("/auth/login"), creds).header("User-Agent", "Mozilla/5.0 " + "x".repeat(400)))
                .andExpect(status().isOk());

        String sessions = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/sessions")
                        .with(eve.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> agents = com.jayway.jsonpath.JsonPath.read(sessions, "$[*].userAgent");
        // Three sessions: register(), the phone, and the browser.
        assertThat(agents).hasSize(3).contains("Lock/1.0 (iPhone; iOS 19)");
        assertThat(agents).anySatisfy(a -> assertThat(a).isNotNull().startsWith("Mozilla/5.0 x").hasSize(300));
    }

    private String login(TestUser user) throws Exception {
        return read(mvc.perform(json(post("/auth/login"), """
                        {"email":"%s","password":"%s"}""".formatted(user.email(), user.password())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(json(post("/auth/refresh"), body(token)));
    }

    private static String body(String token) {
        return """
                {"refreshToken":"%s"}""".formatted(token);
    }
}
