package app.lock.media.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV ONLY. Plays the part of S3 presigned URLs for {@link InMemoryObjectStorage}, so a real client
 * can upload and download media with no MinIO. Like S3 with a signed checksum, it rejects bytes
 * whose SHA-256 does not match the header. It is not authenticated and must never be enabled in
 * production: it only exists when {@code lock.storage.type=memory}.
 */
@RestController
@ConditionalOnProperty(name = "lock.storage.type", havingValue = "memory")
public class LocalStorageController {

    public static final String PATH = "/local-storage";

    private final InMemoryObjectStorage storage;

    LocalStorageController(InMemoryObjectStorage storage) {
        this.storage = storage;
    }

    @PutMapping(PATH + "/**")
    ResponseEntity<Void> put(HttpServletRequest request, @RequestBody byte[] body,
                             @RequestHeader(value = "x-amz-checksum-sha256", required = false) String checksum) {
        if (checksum != null && !checksum.equals(InMemoryObjectStorage.sha256Base64(body))) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        storage.put(key(request), body);
        return ResponseEntity.ok().build();
    }

    @GetMapping(PATH + "/**")
    ResponseEntity<byte[]> get(HttpServletRequest request) {
        return storage.get(key(request))
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    private static String key(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length() + PATH.length() + 1);
    }
}
