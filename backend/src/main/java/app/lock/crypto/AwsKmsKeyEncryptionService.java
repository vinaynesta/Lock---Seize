package app.lock.crypto;

import app.lock.config.LockProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

/**
 * KEK held in AWS KMS: vault data keys are sent to KMS to be wrapped and unwrapped, and the KEK
 * itself never leaves KMS. Credentials come from the default AWS chain (IAM role, env, profile).
 *
 * <p>The encryption context binds every wrapped key to Lock, so a ciphertext from another system
 * using the same KMS key cannot be unwrapped here, and every KMS call is attributable in CloudTrail.
 *
 * <p>Wrapped keys produced by a retired <em>local</em> master key can still be unwrapped when
 * {@code lock.crypto.retired-master-keys} lists it: that is how existing vaults move onto KMS.
 */
@Service
@ConditionalOnProperty(name = "lock.crypto.provider", havingValue = "aws-kms")
public class AwsKmsKeyEncryptionService implements KeyEncryptionService, DisposableBean {

    static final Map<String, String> CONTEXT = Map.of("app", "lock", "purpose", "vault-key");

    private final KmsClient kms;
    private final String keyId;
    private final LocalKeyEncryptionService legacy;

    @Autowired
    public AwsKmsKeyEncryptionService(LockProperties props) {
        this(buildClient(props.crypto()), props.crypto().kmsKeyId(), legacyKeys(props));
    }

    AwsKmsKeyEncryptionService(KmsClient kms, String keyId, LocalKeyEncryptionService legacy) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("lock.crypto.kms-key-id (LOCK_KMS_KEY_ID) is required for aws-kms");
        }
        this.kms = kms;
        this.keyId = keyId;
        this.legacy = legacy;
    }

    @Override
    public String currentKekId() {
        return "aws-kms:" + keyId;
    }

    @Override
    public byte[] wrap(byte[] dataKey) {
        return kms.encrypt(r -> r.keyId(keyId).plaintext(SdkBytes.fromByteArray(dataKey)).encryptionContext(CONTEXT))
                .ciphertextBlob().asByteArray();
    }

    @Override
    public byte[] unwrap(byte[] wrappedKey, String kekId) {
        if (kekId != null && kekId.startsWith("aws-kms:")) {
            // KMS finds the key from the ciphertext; pinning keyId refuses blobs from any other key.
            return kms.decrypt(r -> r.keyId(kekId.substring("aws-kms:".length()))
                            .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey)).encryptionContext(CONTEXT))
                    .plaintext().asByteArray();
        }
        if (legacy == null) {
            throw new IllegalStateException("Vault key was wrapped by a local master key (" + kekId
                    + "); set lock.crypto.retired-master-keys so it can be moved to KMS");
        }
        return legacy.unwrap(wrappedKey, kekId);
    }

    @Override
    public void destroy() {
        kms.close();
    }

    private static KmsClient buildClient(LockProperties.Crypto crypto) {
        KmsClientBuilder builder = KmsClient.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(crypto.kmsRegion()));
        if (crypto.kmsEndpoint() != null && !crypto.kmsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(crypto.kmsEndpoint()));
        }
        return builder.build();
    }

    /**
     * Retired local keys that may still be needed to unwrap during a migration to KMS. Null when
     * none are configured, so a pure-KMS deployment holds no local key at all.
     */
    private static LocalKeyEncryptionService legacyKeys(LockProperties props) {
        List<String> retired = props.crypto().retiredMasterKeys() == null ? List.of()
                : props.crypto().retiredMasterKeys().stream().filter(k -> !k.isBlank()).toList();
        if (retired.isEmpty()) {
            return null;
        }
        // The first retired key takes the "current" slot; this instance only ever unwraps with it.
        return new LocalKeyEncryptionService(retired.getFirst(), retired.subList(1, retired.size()));
    }
}
