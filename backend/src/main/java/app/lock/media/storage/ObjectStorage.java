package app.lock.media.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Object storage holds media bytes. It is never the source of authorization truth (invariant 5):
 * callers authorize first, then hand out short-lived presigned URLs.
 */
public interface ObjectStorage {

    String QUARANTINE_PREFIX = "quarantine/";

    /** Presigned direct upload. The declared SHA-256 is signed, so storage rejects mismatched bytes. */
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, String checksumSha256Base64);

    URI presignDownload(String key);

    Optional<ObjectInfo> head(String key);

    /** Streams an object's bytes, for server-side workers (scanning, thumbnails, export). Caller closes it. */
    InputStream read(String key);

    /** Server-side write of a generated object (derivative, export archive). */
    void write(String key, InputStream bytes, long sizeBytes, String contentType);

    /**
     * Moves an object out of the serving path into {@code quarantine/}, for files that failed the
     * malware scan. Kept, not deleted, so an operator can inspect it.
     */
    void quarantine(String key);

    /** Apply retention so the object cannot be deleted or overwritten by normal credentials. */
    void protect(String key, Instant retainUntil);

    /** Object tag that a bucket lifecycle rule matches to move originals to the cold tier (roadmap 3.3). */
    String COLD_TIER_TAG = "lock-tier";
    String COLD_TIER_VALUE = "cold";

    /**
     * Marks an object for the cold tier (roadmap 3.3) by tagging it {@code lock-tier=cold}. The bucket's
     * lifecycle rule does the transition. Tagging changes neither the bytes nor the version, so
     * checksums, object-lock retention and the integrity proof are unaffected. (Copying an object onto
     * itself with a new storage class would create a new, unlocked version and leave the old one hot.)
     */
    void markCold(String key);

    /** Delete, bypassing governance retention. Only for the deliberate purge and draft cleanup paths. */
    void delete(String key);

    record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {}

    record ObjectInfo(long sizeBytes, String checksumSha256Base64) {}
}
