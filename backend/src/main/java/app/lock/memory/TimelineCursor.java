package app.lock.memory;

import app.lock.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in a timeline: the (occurredAt, id) of the last memory on the previous page.
 * Clients treat the encoded form as opaque, so the format can change without breaking them.
 */
public record TimelineCursor(Instant occurredAt, UUID id) {

    public String encode() {
        String raw = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static TimelineCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            return new TimelineCursor(Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Malformed timeline cursor");
        }
    }
}
