package app.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.lock.memory.MemoryService;
import app.lock.support.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Roadmap 1.3: with several app instances, scheduled jobs must split work, never repeat it. */
class ConcurrentJobsIntegrationTest extends IntegrationTest {

    @Autowired
    MemoryService memoryService;

    @Test
    void concurrentSealJobsLockEachMemoryExactlyOnce() throws Exception {
        // Other tests share the database and may leave memories SEALING; complete them first so the
        // counts below only see this test's memories.
        clock.advance(Duration.ofHours(25));
        while (memoryService.completeDueSeals(100) > 0) {
            // drain
        }
        clock.set(START);

        TestUser ivy = register("Ivy");
        String vaultId = personalVault(ivy);
        int total = 40;
        for (int i = 0; i < total; i++) {
            String id = read(mvc.perform(json(post("/vaults/" + vaultId + "/memories"), """
                            {"title":"Seal %d"}""".formatted(i)).with(ivy.auth()))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
            mvc.perform(post("/memories/" + id + "/lock").with(ivy.auth())).andExpect(status().isOk());
        }
        clock.advance(Duration.ofHours(25));

        // Four "instances" fire the seal job at the same moment, with small batches so they overlap.
        int instances = 4;
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> job = () -> {
            start.await();
            int sealed = 0;
            for (int n; (n = memoryService.completeDueSeals(5)) > 0; ) {
                sealed += n;
            }
            return sealed;
        };
        int sealed = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(instances)) {
            var futures = java.util.stream.IntStream.range(0, instances).mapToObj(i -> pool.submit(job)).toList();
            start.countDown();
            for (Future<Integer> f : futures) {
                sealed += f.get();
            }
        }

        assertThat(sealed).isEqualTo(total);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events a JOIN memories m ON m.id = a.memory_id
                WHERE m.vault_id = ?::uuid AND a.event_type = 'MEMORY_LOCKED'""", Long.class, vaultId))
                .isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memories WHERE vault_id = ?::uuid AND state = 'LOCKED'",
                Long.class, vaultId)).isEqualTo(total);
    }
}
