package app.lock.media.storage;

import app.lock.config.LockProperties;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Storage for tests and quick local runs without MinIO. Not durable.
 *
 * <p>Presigned URLs point at {@link LocalStorageController}, which plays the part of S3 over HTTP
 * so a real client can run the full direct-upload flow in this mode.
 */
@Component
@ConditionalOnProperty(name = "lock.storage.type", havingValue = "memory")
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, Instant> retention = new ConcurrentHashMap<>();
    private final LockProperties.Storage props;
    private final Clock clock;

    public InMemoryObjectStorage(LockProperties lockProps, Clock clock) {
        this.props = lockProps.storage();
        this.clock = clock;
    }

    /** Simulates the client's direct upload to a presigned URL. */
    public void put(String key, byte[] bytes) {
        objects.put(key, bytes.clone());
    }

    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(objects.get(key)).map(byte[]::clone);
    }

    public Optional<Instant> retentionOf(String key) {
        return Optional.ofNullable(retention.get(key));
    }

    /** Simulates silent corruption, for integrity-check tests. */
    public void corrupt(String key) {
        byte[] bytes = objects.get(key);
        if (bytes != null && bytes.length > 0) {
            bytes[0] ^= 0x1;
        }
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, String checksumSha256Base64) {
        return new PresignedUpload(localUrl(key), "PUT",
                Map.of("Content-Type", contentType, "x-amz-checksum-sha256", checksumSha256Base64),
                Instant.now(clock).plus(props.presignTtl()));
    }

    @Override
    public URI presignDownload(String key) {
        return localUrl(key);
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        byte[] bytes = objects.get(key);
        return bytes == null ? Optional.empty() : Optional.of(new ObjectInfo(bytes.length, sha256Base64(bytes)));
    }

    @Override
    public void protect(String key, Instant retainUntil) {
        retention.put(key, retainUntil);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
        retention.remove(key);
    }

    /** Absolute URL on this server when called within a request; a relative path otherwise (tests). */
    private static URI localUrl(String key) {
        String path = LocalStorageController.PATH + "/" + key;
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).build().toUri();
        } catch (IllegalStateException noRequest) {
            return URI.create(path);
        }
    }

    public static String sha256Base64(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
