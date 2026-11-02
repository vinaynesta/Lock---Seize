package app.lock;

import static org.assertj.core.api.Assertions.assertThat;

import app.lock.support.IntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * deploy/prometheus/lock-alerts.yml must only use metrics the API really exports, under their
 * Prometheus names. A renamed meter would otherwise silence its alert without anyone noticing.
 * The scanner is clamav (pointed nowhere) so its probe gauge is registered too.
 */
@TestPropertySource(properties = {"lock.scan.provider=clamav", "lock.scan.clamav-host=127.0.0.1",
        "lock.scan.clamav-port=1", "lock.scan.timeout=PT1S"})
class AlertRulesTest extends IntegrationTest {

    static final Path RULES = Path.of("..", "deploy", "prometheus", "lock-alerts.yml");
    static final Pattern METRIC = Pattern.compile("\\b(lock_[a-z0-9_]+)\\b");

    @Autowired
    MeterRegistry registry;

    @Test
    void everyAlertUsesAnExportedMetric() throws Exception {
        Set<String> exported = prometheusNames(registry);
        Set<String> used = new TreeSet<>();
        for (Map<String, Object> rule : rules()) {
            assertThat(rule).containsKeys("alert", "expr", "labels", "annotations");
            assertThat(((Map<?, ?>) rule.get("labels")).get("severity")).isIn("page", "ticket");
            Matcher m = METRIC.matcher((String) rule.get("expr"));
            while (m.find()) {
                used.add(m.group(1));
            }
        }
        assertThat(used).contains("lock_scan_up", "lock_scan_oldest_age_seconds", "lock_scan_pending",
                "lock_storage_exports_lifecycle");
        assertThat(exported).containsAll(used);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rules() throws Exception {
        Map<String, Object> doc = new Yaml().load(Files.readString(RULES));
        return ((List<Map<String, Object>>) doc.get("groups")).stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("rules")).stream())
                .toList();
    }

    /** Re-registers the app's meters in a Prometheus registry and reads back the exposition names. */
    static Set<String> prometheusNames(MeterRegistry source) {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        for (Meter meter : source.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith("lock.")) {
                continue;
            }
            switch (id.getType()) {
                case COUNTER -> Counter.builder(id.getName()).baseUnit(id.getBaseUnit()).register(prom);
                case GAUGE -> Gauge.builder(id.getName(), () -> 0).baseUnit(id.getBaseUnit()).register(prom);
                default -> { }
            }
        }
        Set<String> names = new TreeSet<>();
        for (String line : prom.scrape().split("\n")) {
            if (!line.startsWith("#") && !line.isBlank()) {
                names.add(line.split("[{ ]", 2)[0]);
            }
        }
        return names;
    }
}
