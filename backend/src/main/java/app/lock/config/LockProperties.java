package app.lock.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lock")
public record LockProperties(
        boolean schedulingEnabled,
        java.util.List<String> corsAllowedOrigins,
        Jwt jwt,
        Crypto crypto,
        Memory memory,
        Storage storage,
        RateLimit rateLimit) {

    /** {@code ttl} is the access-token lifetime; {@code refreshTtl} the sliding refresh-token lifetime. */
    public record Jwt(String secret, String issuer, Duration ttl, Duration refreshTtl) {}

    /**
     * Key-encryption key settings (roadmap 1.4). {@code provider} is {@code local} (KEK from
     * {@code masterKey}) or {@code aws-kms} (KEK stays in AWS KMS under {@code kmsKeyId}).
     * {@code retiredMasterKeys} can only unwrap: they let the re-wrap job move vault keys off an old
     * local key, either to a new local key or to KMS.
     */
    public record Crypto(String provider, String masterKey, List<String> retiredMasterKeys,
                         String kmsKeyId, String kmsRegion, String kmsEndpoint) {

        public boolean isAwsKms() {
            return "aws-kms".equals(provider);
        }
    }

    /**
     * Abuse limits (roadmap 1.2). {@code authRequestsPerMinute} is per client IP and per auth
     * endpoint. {@code maxFailedLogins} wrong passwords within {@code lockout} lock that account
     * (and, separately, re-authentication for that user) until the window passes.
     */
    public record RateLimit(int authRequestsPerMinute, int maxFailedLogins, Duration lockout) {}

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
