package app.lock.identity;

import app.lock.config.LockProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The proof that a password was right, handed out by login when a second step is still needed
 * (roadmap 3.1). An HMAC over the user id and expiry, under a key derived from the JWT secret with
 * its own label. It is deliberately not a JWT: the resource server must never accept it as an
 * access token. Valid for five minutes.
 */
@Component
class MfaTickets {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAC_BYTES = 32;

    private final byte[] key;
    private final Clock clock;

    MfaTickets(LockProperties props, Clock clock) {
        this.key = hmac(Base64.getDecoder().decode(props.jwt().secret()), "lock:mfa-ticket-key:v1".getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    record Ticket(String value, Instant expiresAt) {}

    Ticket issue(UUID userId) {
        Instant expiresAt = Instant.now(clock).plus(TTL);
        ByteBuffer body = ByteBuffer.allocate(24).putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits()).putLong(expiresAt.getEpochSecond());
        byte[] mac = hmac(key, body.array());
        byte[] out = ByteBuffer.allocate(24 + MAC_BYTES).put(body.array()).put(mac).array();
        return new Ticket(Base64.getUrlEncoder().withoutPadding().encodeToString(out), expiresAt);
    }

    /** The user the ticket was issued to, if it is genuine and has not expired. */
    Optional<UUID> verify(String ticket) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(ticket);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != 24 + MAC_BYTES) {
            return Optional.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        byte[] body = new byte[24];
        byte[] mac = new byte[MAC_BYTES];
        buf.get(body).get(mac);
        if (!MessageDigest.isEqual(mac, hmac(key, body))) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(body);
        UUID userId = new UUID(b.getLong(), b.getLong());
        if (!Instant.ofEpochSecond(b.getLong()).isAfter(Instant.now(clock))) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
