package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Roadmap 3.1: two-step verification with TOTP and recovery codes. */
class MfaIntegrationTest extends IntegrationTest {

    @Test
    void enrol_thenSignInNeedsACode_andCodesCannotBeReplayed() throws Exception {
        TestUser ada = register("Ada");
        String secret = read(mvc.perform(post("/account/mfa/setup").with(ada.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/Lock%3A")))
                .andReturn().getResponse().getContentAsString(), "$.secret");

        // Not on until confirmed: login still returns a session.
        login(ada).andExpect(jsonPath("$.accessToken").exists());
        mvc.perform(json(post("/account/mfa/confirm"), code("000000")).with(ada.auth())).andExpect(status().isUnauthorized());
        List<String> recovery = JsonPath.read(mvc.perform(json(post("/account/mfa/confirm"), code(totp(secret, 0))).with(ada.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.recoveryCodes");
        assertThat(recovery).hasSize(10).allMatch(c -> c.matches("[a-z2-9]{5}-[a-z2-9]{5}"));

        // Password alone no longer signs in.
        String ticket = read(login(ada)
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getContentAsString(), "$.mfaToken");
        // The ticket is not an access token.
        mvc.perform(get("/vaults").header("Authorization", "Bearer " + ticket)).andExpect(status().isUnauthorized());

        clock.advance(Duration.ofSeconds(30));
        String code = totp(secret, 0);
        mvc.perform(json(post("/auth/login/mfa"), mfaLogin(ticket, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
        // The same code again, even with a fresh ticket: rejected.
        String again = read(login(ada).andReturn().getResponse().getContentAsString(), "$.mfaToken");
        mvc.perform(json(post("/auth/login/mfa"), mfaLogin(again, code)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));

        // A recovery code works once.
        mvc.perform(json(post("/auth/login/mfa"), mfaLogin(again, recovery.getFirst()))).andExpect(status().isOk());
        String third = read(login(ada).andReturn().getResponse().getContentAsString(), "$.mfaToken");
        mvc.perform(json(post("/auth/login/mfa"), mfaLogin(third, recovery.getFirst()))).andExpect(status().isUnauthorized());
        mvc.perform(get("/account/mfa").with(ada.auth())).andExpect(jsonPath("$.recoveryCodesLeft").value(9));

        // Tickets expire after five minutes.
        clock.advance(Duration.ofMinutes(6));
        mvc.perform(json(post("/auth/login/mfa"), mfaLogin(third, totp(secret, 0))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_TICKET_INVALID"));
    }

    @Test
    void sensitiveOperationsNeedACodeOnceEnabled() throws Exception {
        TestUser bo = register("Bo");
        String vault = personalVault(bo);
        String memory = createMemory(bo, vault, "Keep safe");
        lockNow(bo, memory);
        String secret = enable(bo);

        String deletion = """
                {"password":"%s"}""".formatted(bo.password());
        mvc.perform(json(post("/memories/" + memory + "/deletion-request"), deletion).with(bo.auth()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(json(post("/account/deletion-request"), deletion).with(bo.auth()))
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(post("/vaults/" + vault + "/exports").with(bo.auth())).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(get("/vaults/" + vault + "/export").with(bo.auth())).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));

        clock.advance(Duration.ofSeconds(30));
        mvc.perform(json(post("/memories/" + memory + "/deletion-request"), deletion)
                        .header("X-MFA-Code", totp(secret, 0)).with(bo.auth()))
                .andExpect(status().isAccepted());
        clock.advance(Duration.ofSeconds(30));
        mvc.perform(post("/vaults/" + vault + "/exports").header("X-MFA-Code", totp(secret, 0)).with(bo.auth()))
                .andExpect(status().isAccepted());
        // Leave no pending deletion behind for other tests' deletion jobs.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/memories/" + memory + "/deletion-request").with(bo.auth())).andExpect(status().isOk());
    }

    @Test
    void wrongCodesLockOut_andDisablingNeedsACode() throws Exception {
        TestUser cy = register("Cy");
        String secret = enable(cy);
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/account/mfa/disable"), code("000000")).with(cy.auth())).andExpect(status().isUnauthorized());
        }
        // Locked: even the right code is refused until the window passes.
        clock.advance(Duration.ofSeconds(30));
        mvc.perform(json(post("/account/mfa/disable"), code(totp(secret, 0))).with(cy.auth()))
                .andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofMinutes(16));
        mvc.perform(json(post("/account/mfa/disable"), code(totp(secret, 0))).with(cy.auth())).andExpect(status().isNoContent());
        login(cy).andExpect(jsonPath("$.accessToken").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ?::uuid", Integer.class,
                cy.id().toString())).isZero();
    }

    @Test
    void theSecretIsStoredWrapped() throws Exception {
        TestUser di = register("Di");
        String secret = enable(di);
        byte[] stored = jdbc.queryForObject("SELECT secret_wrapped FROM user_mfa WHERE user_id = ?::uuid", byte[].class, di.id().toString());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(stored).isNotEqualTo(base32Decode(secret));
    }

    private String enable(TestUser user) throws Exception {
        String secret = read(mvc.perform(post("/account/mfa/setup").with(user.auth()))
                .andReturn().getResponse().getContentAsString(), "$.secret");
        mvc.perform(json(post("/account/mfa/confirm"), code(totp(secret, 0))).with(user.auth())).andExpect(status().isOk());
        return secret;
    }

    private org.springframework.test.web.servlet.ResultActions login(TestUser user) throws Exception {
        return mvc.perform(json(post("/auth/login"), """
                {"email":"%s","password":"%s"}""".formatted(user.email(), user.password()))).andExpect(status().isOk());
    }

    private static String code(String code) {
        return """
                {"code":"%s"}""".formatted(code);
    }

    private static String mfaLogin(String ticket, String code) {
        return """
                {"mfaToken":"%s","code":"%s"}""".formatted(ticket, code);
    }

    /** What an authenticator app shows now, {@code offsetSteps} steps from the test clock. */
    private String totp(String base32Secret, int offsetSteps) throws Exception {
        long step = Math.floorDiv(Instant.now(clock).getEpochSecond(), 30) + offsetSteps;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int o = h[h.length - 1] & 0x0F;
        int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
        return String.format("%06d", bin % 1_000_000);
    }

    private static byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
