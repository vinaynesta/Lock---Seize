package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.email.LogEmailSender;
import app.lock.support.IntegrationTest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 2.6: password reset by email. */
class PasswordResetIntegrationTest extends IntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_%-]+)");

    @Autowired
    LogEmailSender mail;

    @Test
    void resetSetsNewPassword_revokesSessions_keepsContentReadable() throws Exception {
        TestUser ada = register("Ada");
        String memory = createMemory(ada, personalVault(ada), "Graduation");
        lockNow(ada, memory);
        String refresh = read(login(ada.email(), ada.password()), "$.refreshToken");

        requestReset(ada.email());
        drainOutbox();
        String token = tokenSentTo(ada.email());
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE token_hash = ?", token)).isZero(); // only the hash is stored

        mvc.perform(json(post("/auth/password-reset/confirm"), """
                        {"token":"%s","newPassword":"a-brand-new-password"}""".formatted(token)))
                .andExpect(status().isNoContent());

        // Old password and old sessions are dead; the new password works.
        mvc.perform(json(post("/auth/login"), credentials(ada.email(), ada.password()))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/auth/refresh"), """
                        {"refreshToken":"%s"}""".formatted(refresh))).andExpect(status().isUnauthorized());
        login(ada.email(), "a-brand-new-password");
        // Vault keys are wrapped by the KEK, not the password: content is still readable.
        mvc.perform(get("/memories/" + memory).with(ada.auth())).andExpect(jsonPath("$.title").value("Graduation"));
        // Single use.
        mvc.perform(json(post("/auth/password-reset/confirm"), """
                        {"token":"%s","newPassword":"yet-another-password"}""".formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        drainOutbox();
        assertThat(mail.sent().getLast().subject()).isEqualTo("Your Lock password was reset");
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_id = ?::uuid AND event_type = 'PASSWORD_RESET'",
                ada.id().toString())).isEqualTo(1);
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoMail() throws Exception {
        int before = mail.sent().size();
        requestReset("nobody-" + java.util.UUID.randomUUID() + "@example.com");
        drainOutbox();
        assertThat(mail.sent()).hasSize(before);
    }

    @Test
    void tokenExpiresAfter30Minutes_andOnlyTheNewestWorks() throws Exception {
        TestUser bo = register("Bo");
        requestReset(bo.email());
        drainOutbox();
        String first = tokenSentTo(bo.email());
        requestReset(bo.email());
        drainOutbox();
        String second = tokenSentTo(bo.email());
        assertThat(second).isNotEqualTo(first);

        confirm(first).andExpect(status().isBadRequest());
        clock.advance(Duration.ofMinutes(31));
        confirm(second).andExpect(status().isBadRequest());
    }

    @Test
    void changingThePasswordInvalidatesOutstandingLinks() throws Exception {
        TestUser cy = register("Cy");
        requestReset(cy.email());
        drainOutbox();
        String token = tokenSentTo(cy.email());
        mvc.perform(json(post("/auth/change-password"), """
                        {"currentPassword":"%s","newPassword":"changed-while-signed-in"}""".formatted(cy.password()))
                        .with(cy.auth()))
                .andExpect(status().isOk());
        confirm(token).andExpect(status().isBadRequest());
    }

    @Test
    void requestsArePerEmailThrottledWithoutRevealingIt() throws Exception {
        TestUser di = register("Di");
        for (int i = 0; i < 5; i++) {
            requestReset(di.email());
        }
        assertThat(count("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?::uuid", di.id().toString())).isEqualTo(3);
    }

    private void requestReset(String email) throws Exception {
        mvc.perform(json(post("/auth/password-reset"), """
                        {"email":"%s"}""".formatted(email)))
                .andExpect(status().isAccepted());
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token) throws Exception {
        return mvc.perform(json(post("/auth/password-reset/confirm"), """
                {"token":"%s","newPassword":"some-new-password"}""".formatted(token)));
    }

    private String tokenSentTo(String email) {
        LogEmailSender.Message m = mail.sent().reversed().stream().filter(x -> x.to().equals(email)).findFirst().orElseThrow();
        Matcher matcher = TOKEN.matcher(m.body());
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private String login(String email, String password) throws Exception {
        return mvc.perform(json(post("/auth/login"), credentials(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
