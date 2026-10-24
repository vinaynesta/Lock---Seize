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
        RateLimit rateLimit,
        Scan scan,
        Export export,
        Account account,
        Email email,
        Push push,
        Transcode transcode,
        Quota quota,
        ColdTier coldTier) {

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

    /**
     * Malware scanning (roadmap 2.2). {@code provider} is {@code clamav} (a clamd sidecar at
     * {@code clamavHost:clamavPort}) or {@code dev} (EICAR test file only; refused in prod).
     */
    public record Scan(String provider, String clamavHost, int clamavPort, Duration timeout) {

        public boolean isDev() {
            return provider == null || "dev".equals(provider);
        }
    }

    /** ZIP exports (roadmap 2.3) are deleted from storage {@code ttl} after they are built. */
    public record Export(Duration ttl) {}

    /** Account deletion (roadmap 2.5) executes {@code deletionGracePeriod} after it is requested. */
    public record Account(Duration deletionGracePeriod) {}

    /**
     * Outgoing email (roadmap 2.6). {@code provider} is {@code log} (dev: kept in memory, body logged
     * only when {@code logBody}) or {@code postmark}. {@code resetUrl} is the client page that takes
     * {@code ?token=}; reset tokens are valid for {@code resetTtl}.
     */
    public record Email(String provider, String from, String resetUrl, Duration resetTtl,
                        String postmarkToken, boolean logBody) {}

    /** Push notifications (roadmap 2.7). {@code provider} is {@code log} (dev) or {@code expo}. */
    public record Push(String provider, String expoAccessToken) {}

    /**
     * Video/audio renditions and decoding of formats ImageIO cannot read (roadmap 2.8, F7).
     * {@code provider} is {@code none} or {@code ffmpeg} (the binary at {@code ffmpegPath}).
     */
    public record Transcode(String provider, String ffmpegPath, Duration timeout) {

        public boolean isEnabled() {
            return "ffmpeg".equals(provider);
        }
    }

    /** Storage quotas (roadmap 3.2): bytes of original media per user, by plan. */
    public record Quota(String defaultPlan, java.util.Map<String, org.springframework.util.unit.DataSize> plans) {}

    /**
     * Cold-tier archival (roadmap 3.3): originals of memories locked longer than {@code after} are
     * tagged for the bucket lifecycle rule that moves them to a cheaper class. Derivatives stay hot so
     * timelines and Relive stay fast.
     */
    public record ColdTier(boolean enabled, Duration after) {}

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
