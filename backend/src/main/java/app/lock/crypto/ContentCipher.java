package app.lock.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Encrypts memory text fields with the vault data key.
 *
 * <p>The associated data binds each ciphertext to its record and field, so a ciphertext copied into
 * another row or column fails to decrypt.
 */
@Component
public class ContentCipher {

    private final KeyEncryptionService kek;

    public ContentCipher(KeyEncryptionService kek) {
        this.kek = kek;
    }

    /** A vault data key as stored: wrapped bytes plus the id of the KEK that wrapped them. */
    public record WrappedKey(byte[] bytes, String kekId) {}

    public WrappedKey newWrappedVaultKey() {
        return new WrappedKey(kek.wrap(AesGcm.newKey()), kek.currentKekId());
    }

    /** Re-wraps a vault key under the current KEK. The data key, and so all content, is unchanged. */
    public WrappedKey rewrap(WrappedKey key) {
        return new WrappedKey(kek.wrap(kek.unwrap(key.bytes(), key.kekId())), kek.currentKekId());
    }

    public String currentKekId() {
        return kek.currentKekId();
    }

    public byte[] encrypt(WrappedKey vaultKey, UUID recordId, String field, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return AesGcm.encrypt(unwrap(vaultKey), plaintext.getBytes(StandardCharsets.UTF_8), aad(recordId, field));
    }

    public String decrypt(WrappedKey vaultKey, UUID recordId, String field, byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return new String(AesGcm.decrypt(unwrap(vaultKey), ciphertext, aad(recordId, field)), StandardCharsets.UTF_8);
    }

    private byte[] unwrap(WrappedKey vaultKey) {
        return kek.unwrap(vaultKey.bytes(), vaultKey.kekId());
    }

    private static byte[] aad(UUID recordId, String field) {
        return (recordId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
