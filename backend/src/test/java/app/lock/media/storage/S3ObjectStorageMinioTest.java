package app.lock.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.lock.config.LockProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * F5: {@link S3ObjectStorage} against a real S3 API (MinIO), including a bucket with object lock.
 * Skipped unless MinIO answers at {@code LOCK_IT_S3_ENDPOINT} (default http://127.0.0.1:9000) with a
 * bucket {@code LOCK_IT_S3_BUCKET} (default lock-media-it) created with {@code --with-lock}:
 *
 * <pre>
 *   docker compose up -d minio          # or: minio server /tmp/minio
 *   mc mb --with-lock local/lock-media-it
 * </pre>
 */
class S3ObjectStorageMinioTest {

    static final String ENDPOINT = env("LOCK_IT_S3_ENDPOINT", "http://127.0.0.1:9000");
    static final String BUCKET = env("LOCK_IT_S3_BUCKET", "lock-media-it");
    static final String ACCESS = env("LOCK_IT_S3_ACCESS_KEY", "lock");
    static final String SECRET = env("LOCK_IT_S3_SECRET_KEY", "lock-secret");

    static S3ObjectStorage storage;
    static S3Client raw;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void requireMinio() {
        assumeTrue(reachable(), "MinIO not running at " + ENDPOINT);
        var props = new LockProperties(false, null, null, null, null,
                new LockProperties.Storage("s3", BUCKET, ENDPOINT, "us-east-1", ACCESS, SECRET, true,
                        Duration.ofMinutes(15), 1, 1, 1, 1),
                null, null, null, null, null, null, null, null, null);
        storage = new S3ObjectStorage(props);
        raw = S3Client.builder()
                .endpointOverride(URI.create(ENDPOINT)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Test
    void presignedUploadWithSignedChecksum_thenHeadAndRead() throws Exception {
        String key = key();
        byte[] bytes = "a photo, more or less".getBytes(StandardCharsets.UTF_8);
        String checksum = InMemoryObjectStorage.sha256Base64(bytes);
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, "image/jpeg", bytes.length, checksum);

        // Bytes that don't match the signed checksum are refused by storage itself.
        byte[] tampered = "a photo, but tampered!".getBytes(StandardCharsets.UTF_8);
        assertThat(put(upload, tampered).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(storage.head(key)).isEmpty();

        assertThat(put(upload, bytes).statusCode()).isEqualTo(200);
        assertThat(storage.head(key)).get().satisfies(info -> {
            assertThat(info.sizeBytes()).isEqualTo(bytes.length);
            assertThat(info.checksumSha256Base64()).isEqualTo(checksum);
        });
        try (InputStream in = storage.read(key)) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        HttpResponse<byte[]> download = http.send(HttpRequest.newBuilder(storage.presignDownload(key)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.body()).isEqualTo(bytes);
    }

    @Test
    void serverSideWrite_andDeletingAMissingDerivativeIsHarmless() throws Exception {
        String key = "derived/" + key() + "/thumb.jpg";
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        storage.write(key, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
        assertThat(storage.head(key)).get().extracting(ObjectStorage.ObjectInfo::sizeBytes).isEqualTo(5L);
        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
        storage.delete("derived/" + key() + "/preview.jpg"); // never existed: no exception
    }

    @Test
    void quarantineMovesTheObject() {
        String key = key();
        storage.write(key, new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/jpeg");
        storage.quarantine(key);
        assertThat(storage.head(key)).isEmpty();
        assertThat(storage.head(ObjectStorage.QUARANTINE_PREFIX + key)).isPresent();
        storage.delete(ObjectStorage.QUARANTINE_PREFIX + key);
    }

    @Test
    void objectLockRetention_blocksPlainDeletes_butThePurgePathBypassesGovernance() {
        String key = key();
        storage.write(key, new ByteArrayInputStream(new byte[] {9, 9, 9}), 3, "image/jpeg");
        storage.protect(key, Instant.now().plus(Duration.ofDays(1)));
        var head = raw.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(head.objectLockModeAsString()).isEqualTo("GOVERNANCE");

        // A normal client deleting the locked version is refused.
        String version = head.versionId();
        assertThatThrownBy(() -> raw.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).versionId(version).build()))
                .hasMessageContaining("protected");

        // Tagging for the cold tier (3.3) neither changes the version nor drops the retention.
        storage.markCold(key);
        var after = raw.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(after.versionId()).isEqualTo(version);
        assertThat(after.objectLockModeAsString()).isEqualTo("GOVERNANCE");
        assertThat(raw.getObjectTagging(GetObjectTaggingRequest.builder().bucket(BUCKET).key(key).build()).tagSet())
                .anySatisfy(t -> assertThat(t.key() + "=" + t.value()).isEqualTo("lock-tier=cold"));

        // The deliberate purge path bypasses governance (objectLockYears > 0).
        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
        // In a versioned bucket a delete leaves a marker; also remove the locked version itself.
        raw.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).versionId(version)
                .bypassGovernanceRetention(true).build());
    }

    private static HttpResponse<String> put(ObjectStorage.PresignedUpload upload, byte[] body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(upload.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        upload.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) {
                req.header(name, value);
            }
        });
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String key() {
        return "it/" + UUID.randomUUID();
    }

    private static boolean reachable() {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/minio/health/live"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
