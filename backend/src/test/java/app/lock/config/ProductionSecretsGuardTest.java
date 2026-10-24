package app.lock.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProductionSecretsGuardTest {

    @Test
    void refusesDevDefaults() {
        var guard = new ProductionSecretsGuard(props(ProductionSecretsGuard.DEV_JWT_SECRET,
                ProductionSecretsGuard.DEV_MASTER_KEY, ProductionSecretsGuard.DEV_S3_SECRET));
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCK_JWT_SECRET, LOCK_MASTER_KEY, LOCK_S3_SECRET_KEY");
    }

    @Test
    void refusesTheDevMalwareScanner() {
        var real = props("cmVhbC1zZWNyZXQ=", "cmVhbC1rZXk=", "real-s3-secret");
        var devScan = new LockProperties(real.schedulingEnabled(), null, real.jwt(), real.crypto(), null, real.storage(), null,
                new LockProperties.Scan("dev", null, 0, Duration.ofMinutes(1)), null, null, real.email(), null, null, null, null);
        assertThatThrownBy(new ProductionSecretsGuard(devScan)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCK_SCAN_PROVIDER");
    }

    @Test
    void acceptsRealSecrets() {
        var guard = new ProductionSecretsGuard(props("cmVhbC1zZWNyZXQ=", "cmVhbC1rZXk=", "real-s3-secret"));
        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void devMasterKeyIsFineWhenTheKekLivesInKms() {
        var props = props("cmVhbC1zZWNyZXQ=", ProductionSecretsGuard.DEV_MASTER_KEY, "real-s3-secret");
        var kms = new LockProperties(props.schedulingEnabled(), null, props.jwt(),
                new LockProperties.Crypto("aws-kms", ProductionSecretsGuard.DEV_MASTER_KEY, null, "alias/lock", "us-east-1", null),
                null, props.storage(), null, props.scan(), null, null, props.email(), null, null, null, null);
        assertThatCode(new ProductionSecretsGuard(kms)::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void refusesTheLogOnlyEmailSender() {
        var real = props("cmVhbC1zZWNyZXQ=", "cmVhbC1rZXk=", "real-s3-secret");
        var logEmail = new LockProperties(real.schedulingEnabled(), null, real.jwt(), real.crypto(), null, real.storage(), null,
                real.scan(), null, null, new LockProperties.Email("log", null, null, Duration.ofMinutes(30), null, false),
                null, null, null, null);
        assertThatThrownBy(new ProductionSecretsGuard(logEmail)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCK_EMAIL_PROVIDER");
    }

    /** If someone changes a default in application.yml, the guard must learn about it. */
    @Test
    void applicationYmlDefaultsMatchTheGuard() throws Exception {
        String yaml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml)
                .contains("LOCK_JWT_SECRET:" + ProductionSecretsGuard.DEV_JWT_SECRET)
                .contains("LOCK_MASTER_KEY:" + ProductionSecretsGuard.DEV_MASTER_KEY)
                .contains("LOCK_S3_SECRET_KEY:" + ProductionSecretsGuard.DEV_S3_SECRET);
    }

    private static LockProperties props(String jwtSecret, String masterKey, String s3Secret) {
        return new LockProperties(true, null,
                new LockProperties.Jwt(jwtSecret, "lock", Duration.ofMinutes(15), Duration.ofDays(30)),
                new LockProperties.Crypto("local", masterKey, null, null, null, null),
                null,
                new LockProperties.Storage("s3", "b", "e", "r", "a", s3Secret, true, Duration.ofMinutes(15), 1, 1, 1, 0),
                null,
                new LockProperties.Scan("clamav", "clamd", 3310, Duration.ofMinutes(1)),
                null,
                null,
                new LockProperties.Email("postmark", "Lock <no-reply@lock.app>", "https://lock.app/reset-password",
                        Duration.ofMinutes(30), "token", false),
                null,
                null,
                null,
                null);
    }
}
