package app.lock.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM using the JDK provider. Output layout: 12-byte nonce || ciphertext+tag. */
public final class AesGcm {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {}

    public static byte[] newKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, RANDOM);
            return gen.generateKey().getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] ct = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] blob, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(key), new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    private static SecretKey secretKey(byte[] key) {
        return new SecretKeySpec(key, "AES");
    }
}
