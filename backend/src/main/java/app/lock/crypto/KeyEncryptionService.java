package app.lock.crypto;

/**
 * Wraps and unwraps vault data keys with a key-encryption key (design doc §15).
 *
 * <p>Implementations: {@link LocalKeyEncryptionService} (KEK in configuration, for development) and
 * {@link AwsKmsKeyEncryptionService} (the KEK never leaves AWS KMS). Each wrapped key records which
 * KEK produced it ({@link #currentKekId()}), so the KEK can be rotated by re-wrapping vault keys
 * without re-encrypting any memory content.
 */
public interface KeyEncryptionService {

    /** Identifies the KEK that {@link #wrap} uses now, e.g. {@code local:3fa9c1d2}. Never secret. */
    String currentKekId();

    byte[] wrap(byte[] dataKey);

    /**
     * Unwraps a key produced by any KEK this service still knows, current or retired.
     * {@code kekId} is null for keys wrapped before KEK ids were recorded.
     */
    byte[] unwrap(byte[] wrappedKey, String kekId);
}
