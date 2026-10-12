package app.lock.media.storage;

import app.lock.config.LockProperties;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** S3-compatible storage: AWS S3, MinIO, Cloudflare R2, Backblaze B2. */
@Component
@ConditionalOnProperty(name = "lock.storage.type", havingValue = "s3", matchIfMissing = true)
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final LockProperties.Storage props;

    public S3ObjectStorage(LockProperties lockProps) {
        this.props = lockProps.storage();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build();
        var region = Region.of(props.region());
        var endpoint = props.endpoint() == null || props.endpoint().isBlank() ? null : URI.create(props.endpoint());

        var clientBuilder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(s3Config);
        var presignerBuilder = S3Presigner.builder()
                .credentialsProvider(credentials)
                .region(region)
                .serviceConfiguration(s3Config);
        if (endpoint != null) {
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, String checksumSha256Base64) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .checksumSHA256(checksumSha256Base64)
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(r -> r
                .signatureDuration(props.presignTtl())
                .putObjectRequest(put));
        Map<String, String> headers = new HashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(URI.create(presigned.url().toString()), "PUT", headers, presigned.expiration());
    }

    @Override
    public URI presignDownload(String key) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(props.bucket()).key(key).build();
        return URI.create(presigner.presignGetObject(r -> r.signatureDuration(props.presignTtl()).getObjectRequest(get))
                .url().toString());
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.bucket()).key(key).checksumMode(ChecksumMode.ENABLED).build());
            return Optional.of(new ObjectInfo(head.contentLength(), head.checksumSHA256()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void protect(String key, Instant retainUntil) {
        s3.putObjectRetention(PutObjectRetentionRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .retention(r -> r.mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(retainUntil))
                .build());
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .bypassGovernanceRetention(props.objectLockYears() > 0)
                .build());
    }
}
