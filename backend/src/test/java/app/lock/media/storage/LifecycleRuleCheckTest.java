package app.lock.media.storage;

import static org.assertj.core.api.Assertions.assertThat;

import app.lock.config.LockProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleAndOperator;
import software.amazon.awssdk.services.s3.model.Tag;

/** Follow-up F6: the startup check of the bucket's exports/ expiry rule. */
class LifecycleRuleCheckTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final InMemoryObjectStorage storage = new InMemoryObjectStorage(props("memory"), Clock.systemUTC());
    final LifecycleRuleCheck check = new LifecycleRuleCheck(storage, props("s3"), registry);

    @Test
    void missingRuleIsReported() {
        storage.setLifecycleExpiryDays(null);
        assertThat(check.check()).isZero();
        assertThat(registry.get("lock.storage.exports.lifecycle").gauge().value()).isZero();
    }

    @Test
    void ruleWithinTheSlackPasses_tooLaxFails() {
        storage.setLifecycleExpiryDays(8); // export TTL 7 days + 1
        assertThat(check.check()).isEqualTo(1);
        storage.setLifecycleExpiryDays(30);
        assertThat(check.check()).isZero();
    }

    @Test
    void unreadableConfigurationIsUnknownNotFatal() {
        var failing = new InMemoryObjectStorage(props("memory"), Clock.systemUTC()) {
            @Override
            public java.util.OptionalInt lifecycleExpiryDays(String prefix) {
                throw new IllegalStateException("AccessDenied");
            }
        };
        assertThat(new LifecycleRuleCheck(failing, props("s3"), new SimpleMeterRegistry()).check()).isEqualTo(-1);
    }

    @Test
    void onlyPlainPrefixRulesCountAsCoveringExports() {
        var exports = LifecycleRule.builder().status(ExpirationStatus.ENABLED).filter(f -> f.prefix("exports/")).build();
        var everything = LifecycleRule.builder().status(ExpirationStatus.ENABLED).filter(f -> f.prefix("")).build();
        var other = LifecycleRule.builder().status(ExpirationStatus.ENABLED).filter(f -> f.prefix("media/")).build();
        var tagged = LifecycleRule.builder().status(ExpirationStatus.ENABLED)
                .filter(f -> f.and(LifecycleRuleAndOperator.builder().prefix("exports/")
                        .tags(Tag.builder().key("k").value("v").build()).build())).build();

        assertThat(S3ObjectStorage.covers(S3ObjectStorage.ruleFilterPrefix(exports), "exports/")).isTrue();
        assertThat(S3ObjectStorage.covers(S3ObjectStorage.ruleFilterPrefix(everything), "exports/")).isTrue();
        assertThat(S3ObjectStorage.covers(S3ObjectStorage.ruleFilterPrefix(other), "exports/")).isFalse();
        assertThat(S3ObjectStorage.covers(S3ObjectStorage.ruleFilterPrefix(tagged), "exports/")).isFalse();
    }

    private static LockProperties props(String storageType) {
        return new LockProperties(false, null, null, null, null,
                new LockProperties.Storage(storageType, "lock-media", null, "us-east-1", "a", "s", true,
                        Duration.ofMinutes(15), 1, 1, 1, 0),
                null, null, new LockProperties.Export(Duration.ofDays(7)), null, null, null, null, null, null);
    }
}
