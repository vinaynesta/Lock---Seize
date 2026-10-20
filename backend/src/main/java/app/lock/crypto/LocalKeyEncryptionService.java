package app.lock.crypto;

import app.lock.config.LockProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * KEK held in configuration. Development only, unless the master key comes from a secret manager.
 * Retired master keys can still unwrap, so vault keys can be moved to a new key or to KMS.
 */
@Service
@ConditionalOnProperty(name = "lock.crypto.provider", havingValue = "local", matchIfMissing = true)
public class LocalKeyEncryptionService implements KeyEncryptionService {

    private static final byte[] AAD = "lock:vault-key:v1".getBytes(StandardCharsets.UTF_8);

    private final String currentId;
    private final byte[] current;
    /** Every local key this instance can unwrap with, current first, by id. */
    private final Map<String, byte[]> known = new LinkedHashMap<>();

    @Autowired
    public LocalKeyEncryptionService(LockProperties props) {
        this(props.crypto().masterKey(), props.crypto().retiredMasterKeys());
    }

    LocalKeyEncryptionService(String masterKey, List<String> retiredMasterKeys) {
        this.current = decode(masterKey, "lock.crypto.master-key");
        this.currentId = idOf(current);
        known.put(currentId, current);
        List<String> retired = retiredMasterKeys == null ? List.of() : retiredMasterKeys;
        for (String r : retired) {
            if (!r.isBlank()) {
                byte[] key = decode(r.trim(), "lock.crypto.retired-master-keys");
                known.putIfAbsent(idOf(key), key);
            }
        }
    }

    @Override
    public String currentKekId() {
        return currentId;
    }

    @Override
    public byte[] wrap(byte[] dataKey) {
        return AesGcm.encrypt(current, dataKey, AAD);
    }

    @Override
    public byte[] unwrap(byte[] wrappedKey, String kekId) {
        if (kekId != null) {
            byte[] key = known.get(kekId);
            if (key == null) {
                throw new IllegalStateException("No master key configured for " + kekId
                        + "; add it to lock.crypto.retired-master-keys until the re-wrap job has run");
            }
            return AesGcm.decrypt(key, wrappedKey, AAD);
        }
        // Wrapped before KEK ids were recorded: try each known key (GCM rejects the wrong one).
        RuntimeException last = null;
        for (byte[] key : known.values()) {
            try {
                return AesGcm.decrypt(key, wrappedKey, AAD);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new IllegalStateException("No configured master key unwraps this vault key", last);
    }

    /**
     * A non-secret fingerprint: the first 8 hex chars of SHA-256 over a fixed label and the key.
     * It identifies which key was used without revealing anything useful about the key itself.
     */
    static String idOf(byte[] key) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("lock:kek-id:v1".getBytes(StandardCharsets.UTF_8));
            return "local:" + HexFormat.of().formatHex(d.digest(key)).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String base64, String property) {
        byte[] key = Base64.getDecoder().decode(base64);
        if (key.length != 32) {
            throw new IllegalStateException(property + " must be 32 bytes (base64)");
        }
        return key;
    }
}
