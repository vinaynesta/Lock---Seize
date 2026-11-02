package app.lock.media.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.lock.config.LockProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ClamAvScannerTest {

    @Test
    void parsesCleanInfectedAndErrorReplies() {
        assertThat(ClamAvScanner.parse("stream: OK").infected()).isFalse();
        var found = ClamAvScanner.parse("stream: Win.Test.EICAR_HDB-1 FOUND");
        assertThat(found.infected()).isTrue();
        assertThat(found.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        // An error is never "clean": it must be retried.
        assertThatThrownBy(() -> ClamAvScanner.parse("INSTREAM size limit exceeded. ERROR"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Talks to a fake clamd to check the INSTREAM framing: chunks with a length prefix, then zero. */
    @Test
    void streamsBytesWithInstreamFraming() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    byte[] command = new byte["zINSTREAM\0".length()];
                    in.readFully(command);
                    assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    int len;
                    while ((len = in.readInt()) > 0) {
                        body.write(in.readNBytes(len));
                    }
                    OutputStream out = s.getOutputStream();
                    out.write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return body.toByteArray();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            var props = new LockProperties(false, null, null, null, null, null, null,
                    new LockProperties.Scan("clamav", "localhost", server.getLocalPort(), Duration.ofSeconds(5)), null, null, null, null, null, null, null);
            byte[] payload = new byte[200_000]; // more than one 64 KiB chunk
            payload[150_000] = 42;

            var verdict = new ClamAvScanner(props).scan(new ByteArrayInputStream(payload));

            assertThat(verdict.infected()).isFalse();
            assertThat(received.get()).isEqualTo(payload);
        }
    }

    /** Follow-up F3: the probe behind the lock.scan.up gauge. */
    @Test
    void pingIsTrueOnlyForPong() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) {
                    byte[] command = new DataInputStream(s.getInputStream()).readNBytes("zPING\0".length());
                    if (new String(command, StandardCharsets.US_ASCII).equals("zPING\0")) {
                        s.getOutputStream().write("PONG\0".getBytes(StandardCharsets.US_ASCII));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(new ClamAvScanner(scanProps(server.getLocalPort())).ping()).isTrue();
        }
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertThat(new ClamAvScanner(scanProps(closed)).ping()).isFalse(); // never throws
    }

    private static LockProperties scanProps(int port) {
        return new LockProperties(false, null, null, null, null, null, null,
                new LockProperties.Scan("clamav", "localhost", port, Duration.ofSeconds(2)), null, null, null, null, null, null, null);
    }
}
