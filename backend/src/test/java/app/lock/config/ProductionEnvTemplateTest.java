package app.lock.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Follow-up F4: deploy/production.env.example is the checklist operators copy for every prod
 * deployment. Every variable ProductionSecretsGuard refuses to start without must be in it and
 * marked [guarded], with a value the guard accepts, and the template must never carry a real secret.
 */
class ProductionEnvTemplateTest {

    static final Path TEMPLATE = Path.of("..", "deploy", "production.env.example");
    static final Path GUARD = Path.of("src", "main", "java", "app", "lock", "config", "ProductionSecretsGuard.java");

    @Test
    void everyGuardedVariableIsInTheTemplate() throws Exception {
        Set<String> guarded = new TreeSet<>();
        Matcher m = Pattern.compile("problems\\.add\\(\"(LOCK_[A-Z0-9_]+)\"\\)").matcher(Files.readString(GUARD));
        while (m.find()) {
            guarded.add(m.group(1));
        }
        assertThat(guarded).contains("LOCK_SCAN_PROVIDER", "LOCK_EMAIL_PROVIDER", "LOCK_JWT_SECRET");

        String template = Files.readString(TEMPLATE);
        for (String name : guarded) {
            assertThat(template).as(name).containsPattern("(?m)^#? ?" + name + "=.*\\[guarded]");
        }
    }

    @Test
    void valuesAreProductionSettingsOrPlaceholders() throws Exception {
        Map<String, String> env = parse(Files.readString(TEMPLATE));
        assertThat(env).containsEntry("SPRING_PROFILES_ACTIVE", "prod")
                .containsEntry("LOCK_SCAN_PROVIDER", "clamav")
                .containsEntry("LOCK_RATE_LIMIT_STORE", "postgres")
                .containsEntry("LOCK_EMAIL_PROVIDER", "postmark")
                .containsEntry("LOCK_STORAGE_TYPE", "s3")
                .containsEntry("LOCK_TRANSCODE_PROVIDER", "ffmpeg");
        for (String secret : Set.of("LOCK_JWT_SECRET", "LOCK_S3_SECRET_KEY", "LOCK_DB_PASSWORD", "LOCK_POSTMARK_TOKEN",
                "LOCK_EXPO_ACCESS_TOKEN", "LOCK_S3_ACCESS_KEY")) {
            assertThat(env.get(secret)).as(secret).startsWith("<").endsWith(">");
        }
        assertThat(env.values()).doesNotContain(ProductionSecretsGuard.DEV_JWT_SECRET,
                ProductionSecretsGuard.DEV_MASTER_KEY, ProductionSecretsGuard.DEV_S3_SECRET);
    }

    /** KEY=value lines, ignoring comments and trailing "# ..." notes. */
    static Map<String, String> parse(String text) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) {
                continue;
            }
            String key = l.substring(0, l.indexOf('='));
            String value = l.substring(l.indexOf('=') + 1).replaceAll("\\s+#.*$", "").strip();
            env.put(key, value);
        }
        return env;
    }
}
