package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.memory.MemoryService;
import app.lock.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 1.5: request ids, health probes and lifecycle metrics. */
class ObservabilityIntegrationTest extends IntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    MemoryService memoryService;

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        String generated = mvc.perform(get("/actuator/health"))
                .andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(generated).hasSizeGreaterThan(10);

        mvc.perform(get("/actuator/health").header("X-Request-Id", "lb-abc.123"))
                .andExpect(header().string("X-Request-Id", "lb-abc.123"));
        // Anything that could forge or break a log line is replaced, not echoed.
        String hostile = mvc.perform(get("/actuator/health").header("X-Request-Id", "x\r\nINFO fake log line"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(hostile).doesNotContain("fake").doesNotContain("\n");
    }

    @Test
    void readinessIncludesDatabaseAndStorageWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void metricsAreNeverServedToAppUsers() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        TestUser nosy = register("Nosy");
        mvc.perform(get("/actuator/prometheus").with(nosy.auth())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(nosy.auth())).andExpect(status().isForbidden());
    }

    @Test
    void lockingAMemoryIsCounted() throws Exception {
        double before = registry.counter("lock.memories.locked").count();
        TestUser mia = register("Mia");
        String id = read(mvc.perform(json(post("/vaults/" + personalVault(mia) + "/memories"), """
                        {"title":"Counted"}""").with(mia.auth()))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/memories/" + id + "/lock").with(mia.auth())).andExpect(status().isOk());

        assertThat(registry.get("lock.seal.lag").gauge().value()).isZero(); // still inside cooling-off
        clock.advance(Duration.ofHours(25));
        assertThat(registry.get("lock.seal.lag").gauge().value()).isGreaterThan(3000); // overdue by ~1h
        memoryService.completeDueSeals(100);

        assertThat(registry.counter("lock.memories.locked").count()).isEqualTo(before + 1);
        assertThat(registry.get("lock.seal.lag").gauge().value()).isZero();
        assertThat(registry.get("lock.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
    }
}
