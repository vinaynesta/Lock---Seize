package app.lock.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 secret "12345678901234567890", truncated to 6 digits. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238TestVectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfDriftButNotReplays() {
        Instant now = Instant.ofEpochSecond(1_800_000_000);
        long step = Totp.step(now);
        String previous = Totp.code(RFC_SECRET, step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, null)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, previous, now, step - 1)).isEmpty(); // already used
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null)).isEmpty(); // too old
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEmpty();
    }

    @Test
    void base32MatchesRfc4648() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }
}
