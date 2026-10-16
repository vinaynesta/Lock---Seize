package app.lock.media.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Object storage holds media bytes. It is never the source of authorization truth (invariant 5):
 * callers authorize first, then hand out short-lived presigned URLs.
 */
public interface ObjectStorage {

    /** Presigned direct upload. The declared SHA-256 is signed, so storage rejects mismatched bytes. */
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, String checksumSha256Base64);

    URI presignDownload(String key);

    Optional<ObjectInfo> head(String key);

    /** Apply retention so the object cannot be deleted or overwritten by normal credentials. */
    void protect(String key, Instant retainUntil);

    /** Delete, bypassing governance retention. Only for the deliberate purge and draft cleanup paths. */
    void delete(String key);

    record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {}

    record ObjectInfo(long sizeBytes, String checksumSha256Base64) {}
}
