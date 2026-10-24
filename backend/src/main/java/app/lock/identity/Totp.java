package app.lock.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238): HMAC-SHA1, 30-second steps, 6 digits, which is what
 * every authenticator app expects by default.
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    /** Accept the previous and next step too: phone clocks drift and people type slowly. */
    private static final int WINDOW = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** 160 bits, the size RFC 4226 recommends. */
    static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
    }

    /**
     * The step a code belongs to, if it matches one within the window and is newer than
     * {@code lastUsedStep} (replay protection). Empty if it does not match.
     */
    static OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        for (long s = current - WINDOW; s <= current + WINDOW; s++) {
            if (lastUsedStep != null && s <= lastUsedStep) {
                continue;
            }
            // Constant-time comparison, so response timing reveals nothing about the right code.
            if (MessageDigest.isEqual(code(secret, s).getBytes(), code.getBytes())) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0F;
            int binary = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16)
                    | ((h[offset + 2] & 0xFF) << 8) | (h[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }
}
