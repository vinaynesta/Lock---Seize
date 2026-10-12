package app.lock.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lock")
public record LockProperties(
        boolean schedulingEnabled,
        java.util.List<String> corsAllowedOrigins,
        Jwt jwt,
        Crypto crypto,
        Memory memory,
        Storage storage) {

    public record Jwt(String secret, String issuer, Duration ttl) {}

    public record Crypto(String masterKey) {}

    public record Memory(Duration coolingOff, Duration deletionGracePeriod, Duration uploadExpiry) {}

    public record Storage(
            String type,
            String bucket,
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            Duration presignTtl,
            long maxPhotoBytes,
            long maxVideoBytes,
            long maxAudioBytes,
            int objectLockYears) {}
}
