package app.lock.crypto;

import app.lock.common.ApiException;
import java.util.Base64;
import java.util.Map;

/**
 * Shape checks for end-to-end encrypted material (Sealed Vault, roadmap 3.6). The server cannot
 * decrypt any of it, so it only checks that blobs have the documented layout and sane sizes; that keeps
 * a buggy client from storing something no device could ever open. Layouts (client: lib/sealed.ts):
 * <ul>
 *   <li>wrapped secret key: {@code 0x01 || nonce(12) || AES-GCM(32-byte key) + tag(16)} = 61 bytes</li>
 *   <li>wrapped vault key: {@code 0x02 || ephemeral X25519 public key(32) || nonce(12) || ct(32) + tag(16)} = 93 bytes.
 *       Version 2 (security review F2) authenticates the wrapping member: the AES key is derived from both
 *       the ephemeral and the sender's static X25519 key. Version-1 copies already stored stay readable,
 *       but new uploads must be version 2.</li>
 *   <li>content field: {@code nonce(12) || ciphertext + tag(16)}, the same layout as server-side content</li>
 * </ul>
 */
public final class E2eFormats {

    public static final int FORMAT_V1 = 1;
    public static final int VAULT_KEY_FORMAT_V2 = 2;
    public static final int PUBLIC_KEY_BYTES = 32;
    public static final int WRAPPED_SECRET_KEY_BYTES = 1 + 12 + 32 + 16;
    public static final int WRAPPED_VAULT_KEY_BYTES = 1 + 32 + 12 + 32 + 16;
    /** Nonce plus tag: the ciphertext of an empty string. */
    public static final int CONTENT_OVERHEAD = 12 + 16;

    /**
     * Media (roadmap 3.6d) is encrypted in 1 MiB chunks: a 16-byte header ({@code "LKM1"}, version,
     * 11-byte nonce prefix) then, per chunk, ciphertext + 16-byte tag. Returns the bytes added.
     */
    public static final int MEDIA_CHUNK = 1 << 20;
    public static final int MEDIA_HEADER = 16;
    public static final int MEDIA_TAG = 16;

    public static long mediaOverhead(long ciphertextBytes) {
        long body = Math.max(0, ciphertextBytes - MEDIA_HEADER);
        long chunks = Math.max(1, (body + MEDIA_CHUNK + MEDIA_TAG - 1) / (MEDIA_CHUNK + MEDIA_TAG));
        return MEDIA_HEADER + chunks * MEDIA_TAG;
    }

    /** Argon2id floor (OWASP: 19 MiB, 2 passes) and ceiling (so no device is asked for gigabytes). */
    static final int MIN_MEMORY_KIB = 19_456;
    static final int MAX_MEMORY_KIB = 262_144;
    static final int MIN_PASSES = 2;
    static final int MAX_PASSES = 10;

    private E2eFormats() {}

    public static byte[] decode(String base64, String field) {
        if (base64 == null || base64.isBlank()) {
            throw invalid(field, "is required");
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "is not valid base64");
        }
    }

    public static byte[] publicKey(String base64) {
        byte[] key = decode(base64, "publicKey");
        if (key.length != PUBLIC_KEY_BYTES) {
            throw invalid("publicKey", "must be a 32-byte X25519 public key");
        }
        return key;
    }

    public static byte[] wrappedSecretKey(String base64, String field) {
        return versioned(decode(base64, field), WRAPPED_SECRET_KEY_BYTES, field);
    }

    public static byte[] wrappedVaultKey(String base64, String field) {
        byte[] blob = decode(base64, field);
        if (blob.length == WRAPPED_VAULT_KEY_BYTES && blob[0] == FORMAT_V1) {
            throw ApiException.badRequest("LEGACY_KEY_FORMAT",
                    field + " uses the unauthenticated version-1 wrap; update the app to share vault keys");
        }
        if (blob.length != WRAPPED_VAULT_KEY_BYTES || blob[0] != VAULT_KEY_FORMAT_V2) {
            throw invalid(field, "must be a version-2 wrapped key of " + WRAPPED_VAULT_KEY_BYTES + " bytes");
        }
        return blob;
    }

    /** One encrypted content field; null stays null unless {@code required}. */
    public static byte[] content(String base64, String field, int maxPlaintextChars, boolean required) {
        if (base64 == null) {
            if (required) {
                throw invalid(field, "is required");
            }
            return null;
        }
        byte[] ct = decode(base64, field);
        // UTF-8 is at most 4 bytes per char; anything longer than that cannot be a valid field.
        if (ct.length < CONTENT_OVERHEAD + (required ? 1 : 0) || ct.length > CONTENT_OVERHEAD + 4L * maxPlaintextChars) {
            throw invalid(field, "has an impossible length for this field");
        }
        return ct;
    }

    /**
     * KDF parameters stored next to a wrapped secret key, so any device can re-derive the wrapping key.
     * {@code argon2id} (for a passphrase) or, when {@code allowHkdf}, {@code hkdf-sha256} (for the
     * recovery phrase, which already has 256 bits of entropy and needs no stretching).
     */
    public static Map<String, Object> kdf(Map<String, Object> params, String field, boolean allowHkdf) {
        if (params == null) {
            throw invalid(field, "is required");
        }
        Object alg = params.get("alg");
        byte[] salt = decode(params.get("salt") instanceof String s ? s : null, field + ".salt");
        if (salt.length < 16 || salt.length > 64) {
            throw invalid(field + ".salt", "must be 16 to 64 bytes");
        }
        if ("argon2id".equals(alg)) {
            int m = intParam(params, "m", field);
            int t = intParam(params, "t", field);
            int p = intParam(params, "p", field);
            if (m < MIN_MEMORY_KIB || m > MAX_MEMORY_KIB || t < MIN_PASSES || t > MAX_PASSES || p < 1 || p > 4) {
                throw invalid(field, "Argon2id parameters are outside the allowed range (m 19456-262144 KiB, t 2-10, p 1-4)");
            }
            return Map.of("alg", "argon2id", "salt", params.get("salt"), "m", m, "t", t, "p", p);
        }
        if (allowHkdf && "hkdf-sha256".equals(alg)) {
            return Map.of("alg", "hkdf-sha256", "salt", params.get("salt"));
        }
        throw invalid(field + ".alg", allowHkdf ? "must be argon2id or hkdf-sha256" : "must be argon2id");
    }

    private static int intParam(Map<String, Object> params, String name, String field) {
        if (params.get(name) instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw invalid(field + "." + name, "must be an integer");
    }

    private static byte[] versioned(byte[] blob, int length, String field) {
        if (blob.length != length || blob[0] != FORMAT_V1) {
            throw invalid(field, "must be a version-1 wrapped key of " + length + " bytes");
        }
        return blob;
    }

    private static ApiException invalid(String field, String problem) {
        return ApiException.badRequest("INVALID_E2E_PAYLOAD", field + " " + problem);
    }
}
