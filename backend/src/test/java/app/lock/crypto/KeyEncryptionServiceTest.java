package app.lock.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.IncorrectKeyException;
import software.amazon.awssdk.services.kms.model.InvalidCiphertextException;

class KeyEncryptionServiceTest {

    private static final String OLD = Base64.getEncoder().encodeToString(AesGcm.newKey());
    private static final String NEW = Base64.getEncoder().encodeToString(AesGcm.newKey());

    @Test
    void localKekIdIsAStableNonSecretFingerprint() {
        var a = new LocalKeyEncryptionService(OLD, null);
        var b = new LocalKeyEncryptionService(OLD, null);
        assertThat(a.currentKekId()).isEqualTo(b.currentKekId()).startsWith("local:").hasSize("local:".length() + 8);
        assertThat(a.currentKekId()).isNotEqualTo(new LocalKeyEncryptionService(NEW, null).currentKekId());
        assertThat(a.currentKekId()).doesNotContain(OLD);
    }

    @Test
    void retiredLocalKeyStillUnwrapsAfterRotation() {
        var before = new LocalKeyEncryptionService(OLD, null);
        byte[] dataKey = AesGcm.newKey();
        byte[] wrapped = before.wrap(dataKey);

        var after = new LocalKeyEncryptionService(NEW, List.of(OLD));
        assertThat(after.unwrap(wrapped, before.currentKekId())).isEqualTo(dataKey);
        assertThat(after.unwrap(wrapped, null)).isEqualTo(dataKey); // legacy rows without a kek id

        var forgotten = new LocalKeyEncryptionService(NEW, null);
        assertThatThrownBy(() -> forgotten.unwrap(wrapped, before.currentKekId()))
                .hasMessageContaining("retired-master-keys");
    }

    @Test
    void kmsWrapsWithEncryptionContextAndRefusesOtherKeys() {
        var fake = new FakeKms();
        var kms = new AwsKmsKeyEncryptionService(fake, "alias/lock", null);
        byte[] dataKey = AesGcm.newKey();

        byte[] wrapped = kms.wrap(dataKey);
        assertThat(kms.currentKekId()).isEqualTo("aws-kms:alias/lock");
        assertThat(wrapped).isNotEqualTo(dataKey);
        assertThat(fake.lastContext).isEqualTo(AwsKmsKeyEncryptionService.CONTEXT);
        assertThat(kms.unwrap(wrapped, kms.currentKekId())).isEqualTo(dataKey);

        assertThatThrownBy(() -> kms.unwrap(wrapped, "aws-kms:alias/someone-else"))
                .isInstanceOf(IncorrectKeyException.class);
    }

    @Test
    void kmsCanUnwrapRetiredLocalKeysDuringMigration() {
        var local = new LocalKeyEncryptionService(OLD, null);
        byte[] dataKey = AesGcm.newKey();
        byte[] wrappedLocally = local.wrap(dataKey);

        var migrating = new AwsKmsKeyEncryptionService(new FakeKms(), "alias/lock", new LocalKeyEncryptionService(OLD, null));
        assertThat(migrating.unwrap(wrappedLocally, local.currentKekId())).isEqualTo(dataKey);

        var pureKms = new AwsKmsKeyEncryptionService(new FakeKms(), "alias/lock", null);
        assertThatThrownBy(() -> pureKms.unwrap(wrappedLocally, local.currentKekId()))
                .hasMessageContaining("retired-master-keys");
    }

    @Test
    void kmsRequiresAKeyId() {
        assertThatThrownBy(() -> new AwsKmsKeyEncryptionService(new FakeKms(), " ", null))
                .hasMessageContaining("LOCK_KMS_KEY_ID");
    }

    /**
     * In-memory stand-in for KMS: "encrypts" with a fixed AES key and embeds the key id and the
     * encryption context, then enforces both on decrypt the way real KMS does.
     */
    static final class FakeKms implements KmsClient {
        private final byte[] secret = AesGcm.newKey();
        Map<String, String> lastContext;

        @Override
        public EncryptResponse encrypt(EncryptRequest r) {
            lastContext = r.encryptionContext();
            byte[] aad = (r.keyId() + "|" + new java.util.TreeMap<>(r.encryptionContext())).getBytes();
            byte[] ct = AesGcm.encrypt(secret, r.plaintext().asByteArray(), aad);
            byte[] id = r.keyId().getBytes();
            byte[] blob = ByteBuffer.allocate(4 + id.length + ct.length).putInt(id.length).put(id).put(ct).array();
            return EncryptResponse.builder().keyId(r.keyId()).ciphertextBlob(SdkBytes.fromByteArray(blob)).build();
        }

        @Override
        public DecryptResponse decrypt(DecryptRequest r) {
            ByteBuffer buf = ByteBuffer.wrap(r.ciphertextBlob().asByteArray());
            byte[] id = new byte[buf.getInt()];
            buf.get(id);
            String keyId = new String(id);
            if (r.keyId() != null && !r.keyId().equals(keyId)) {
                throw IncorrectKeyException.builder().message("wrong key").build();
            }
            byte[] ct = Arrays.copyOfRange(buf.array(), buf.position(), buf.limit());
            byte[] aad = (keyId + "|" + new java.util.TreeMap<>(r.encryptionContext())).getBytes();
            try {
                return DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(AesGcm.decrypt(secret, ct, aad))).build();
            } catch (RuntimeException e) {
                throw InvalidCiphertextException.builder().message("bad context").build();
            }
        }

        @Override
        public String serviceName() {
            return "kms";
        }

        @Override
        public void close() {}
    }
}
