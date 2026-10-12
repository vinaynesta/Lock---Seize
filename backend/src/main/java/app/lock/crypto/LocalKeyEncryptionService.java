package app.lock.crypto;

import app.lock.config.LockProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class LocalKeyEncryptionService implements KeyEncryptionService {

    private static final byte[] AAD = "lock:vault-key:v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] masterKey;

    public LocalKeyEncryptionService(LockProperties props) {
        this.masterKey = Base64.getDecoder().decode(props.crypto().masterKey());
        if (masterKey.length != 32) {
            throw new IllegalStateException("lock.crypto.master-key must be 32 bytes (base64)");
        }
    }

    @Override
    public byte[] wrap(byte[] dataKey) {
        return AesGcm.encrypt(masterKey, dataKey, AAD);
    }

    @Override
    public byte[] unwrap(byte[] wrappedKey) {
        return AesGcm.decrypt(masterKey, wrappedKey, AAD);
    }
}
