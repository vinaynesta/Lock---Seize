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

    public byte[] newWrappedVaultKey() {
        return kek.wrap(AesGcm.newKey());
    }

    public byte[] encrypt(byte[] wrappedVaultKey, UUID recordId, String field, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        return AesGcm.encrypt(kek.unwrap(wrappedVaultKey), plaintext.getBytes(StandardCharsets.UTF_8), aad(recordId, field));
    }

    public String decrypt(byte[] wrappedVaultKey, UUID recordId, String field, byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return new String(AesGcm.decrypt(kek.unwrap(wrappedVaultKey), ciphertext, aad(recordId, field)), StandardCharsets.UTF_8);
    }

    private static byte[] aad(UUID recordId, String field) {
        return (recordId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
