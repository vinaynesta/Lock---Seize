package app.lock.crypto;

/**
 * Wraps and unwraps vault data keys with a key-encryption key (design doc §15).
 *
 * <p>The local implementation keeps the KEK in configuration. Production should swap in an
 * implementation backed by a managed KMS/HSM so the KEK never leaves it.
 */
public interface KeyEncryptionService {

    byte[] wrap(byte[] dataKey);

    byte[] unwrap(byte[] wrappedKey);
}
