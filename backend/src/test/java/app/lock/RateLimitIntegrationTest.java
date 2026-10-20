package app.lock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Roadmap 1.2: per-IP limits on auth endpoints and per-account lockout after failed passwords. */
class RateLimitIntegrationTest extends IntegrationTest {

    @Test
    void authEndpointsAreLimitedPerIp() throws Exception {
        String body = """
                {"email":"nobody@example.com","password":"x"}""";
        for (int i = 0; i < 20; i++) {
            mvc.perform(json(post("/auth/login"), body).with(from("10.0.0.1"))).andExpect(status().isUnauthorized());
            rateLimiter.reset("fail:login:nobody@example.com"); // isolate the IP limit from the account lockout
        }
        mvc.perform(json(post("/auth/login"), body).with(from("10.0.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // Another client is unaffected, and the limit lifts after the window.
        mvc.perform(json(post("/auth/login"), body).with(from("10.0.0.2"))).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofSeconds(61));
        mvc.perform(json(post("/auth/login"), body).with(from("10.0.0.1"))).andExpect(status().isUnauthorized());
    }

    @Test
    void accountLocksAfterRepeatedWrongPasswordsEvenFromManyIps() throws Exception {
        TestUser jade = register("Jade");
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/auth/login"), login(jade.email(), "wrong-password-" + i)).with(from("10.1.0." + i)))
                    .andExpect(status().isUnauthorized());
        }
        // Even the right password is refused during the lockout.
        mvc.perform(json(post("/auth/login"), login(jade.email(), jade.password())).with(from("10.2.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(header().exists("Retry-After"));

        clock.advance(Duration.ofMinutes(16));
        mvc.perform(json(post("/auth/login"), login(jade.email(), jade.password())).with(from("10.2.0.1")))
                .andExpect(status().isOk());
    }

    @Test
    void lockoutLooksTheSameForUnknownEmails() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/auth/login"), login("ghost@example.com", "guess-" + i))).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/auth/login"), login("ghost@example.com", "guess")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        TestUser kim = register("Kim");
        for (int i = 0; i < 4; i++) {
            mvc.perform(json(post("/auth/login"), login(kim.email(), "nope-" + i))).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/auth/login"), login(kim.email(), kim.password()))).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            mvc.perform(json(post("/auth/login"), login(kim.email(), "nope-again-" + i))).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/auth/login"), login(kim.email(), kim.password()))).andExpect(status().isOk());
    }

    @Test
    void reauthenticationIsLockedAfterRepeatedWrongPasswords() throws Exception {
        TestUser leo = register("Leo");
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/auth/change-password"), changePassword("wrong-current-" + i)).with(leo.auth()))
                    .andExpect(status().isUnauthorized());
        }
        // A stolen access token cannot keep guessing: the right password is refused too.
        mvc.perform(json(post("/auth/change-password"), changePassword(leo.password())).with(leo.auth()))
                .andExpect(status().isTooManyRequests());
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String login(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private static String changePassword(String current) {
        return """
                {"currentPassword":"%s","newPassword":"a-brand-new-password"}""".formatted(current);
    }
}
