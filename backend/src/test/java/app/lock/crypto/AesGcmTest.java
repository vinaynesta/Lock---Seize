package app.lock.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AesGcmTest {

    private static final byte[] AAD = "memory-1:title".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTrips() {
        byte[] key = AesGcm.newKey();
        byte[] ct = AesGcm.encrypt(key, "hello".getBytes(StandardCharsets.UTF_8), AAD);
        assertThat(new String(AesGcm.decrypt(key, ct, AAD), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void sameInputGivesDifferentCiphertext() {
        byte[] key = AesGcm.newKey();
        byte[] pt = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(AesGcm.encrypt(key, pt, AAD)).isNotEqualTo(AesGcm.encrypt(key, pt, AAD));
    }

    @Test
    void rejectsTamperingWrongKeyAndWrongContext() {
        byte[] key = AesGcm.newKey();
        byte[] ct = AesGcm.encrypt(key, "hello".getBytes(StandardCharsets.UTF_8), AAD);

        byte[] tampered = ct.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> AesGcm.decrypt(key, tampered, AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AesGcm.decrypt(AesGcm.newKey(), ct, AAD)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AesGcm.decrypt(key, ct, "memory-2:title".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }
}
