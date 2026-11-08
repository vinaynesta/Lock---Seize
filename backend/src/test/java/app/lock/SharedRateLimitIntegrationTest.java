package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.security.PostgresRateLimiter;
import app.lock.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Roadmap 1.2 follow-up: with {@code lock.rate-limit.store=postgres} every instance shares the counts,
 * so N instances no longer give an attacker N times the limit.
 */
@TestPropertySource(properties = "lock.rate-limit.store=postgres")
class SharedRateLimitIntegrationTest extends IntegrationTest {

    @Test
    void thePostgresStoreIsUsed_andCountsSlideWithTheWindow() {
        assertThat(rateLimiter).isInstanceOf(PostgresRateLimiter.class);
        Duration minute = Duration.ofMinutes(1);
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire("t:a", 3, minute)).isZero();
        }
        Duration wait = rateLimiter.tryAcquire("t:a", 3, minute);
        assertThat(wait).isPositive().isLessThanOrEqualTo(minute);
        assertThat(rateLimiter.tryAcquire("t:b", 3, minute)).isZero(); // keys are independent

        clock.advance(Duration.ofSeconds(61));
        assertThat(rateLimiter.tryAcquire("t:a", 3, minute)).isZero();
        assertThat(rateLimiter.count("t:a", minute)).isEqualTo(1);
    }

    @Test
    void concurrentAttemptsNeverExceedTheLimit() throws Exception {
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            attempts.add(() -> rateLimiter.tryAcquire("t:race", 5, Duration.ofMinutes(1)).isZero());
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            long allowed = pool.invokeAll(attempts).stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).count();
            assertThat(allowed).isEqualTo(5);
        }
    }

    @Test
    void resetByPrefix_andOldRowsAreSwept() {
        rateLimiter.record("fail:login:a@x.io|ip:1");
        rateLimiter.record("fail:login:a@x.io|ip:2");
        rateLimiter.record("fail:login:a@x.io_other"); // LIKE wildcards in the prefix are escaped
        rateLimiter.resetPrefix("fail:login:a@x.io|");
        assertThat(rateLimiter.count("fail:login:a@x.io|ip:1", Duration.ofHours(1))).isZero();
        assertThat(rateLimiter.count("fail:login:a@x.io_other", Duration.ofHours(1))).isEqualTo(1);

        clock.advance(Duration.ofHours(3));
        assertThat(((PostgresRateLimiter) rateLimiter).sweep()).isEqualTo(1);
    }

    @Test
    void aFailedLoginIsCountedEvenThoughTheLoginRollsBack() throws Exception {
        TestUser ana = register("Ana");
        for (int i = 0; i < 5; i++) {
            mvc.perform(json(post("/auth/login"), """
                    {"email":"%s","password":"wrong-%d"}""".formatted(ana.email(), i))).andExpect(status().isUnauthorized());
        }
        mvc.perform(json(post("/auth/login"), """
                        {"email":"%s","password":"%s"}""".formatted(ana.email(), ana.password())))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }
}
