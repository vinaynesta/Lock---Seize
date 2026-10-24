package app.lock.media.scan;

import app.lock.config.LockProperties;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Streams bytes to a clamd sidecar with its INSTREAM command: each chunk is sent as a 4-byte
 * big-endian length followed by the data, and a zero length ends the stream. clamd answers
 * {@code stream: OK} or {@code stream: <signature> FOUND}. Anything else is an error, so the scan is
 * retried rather than treated as clean. clamd's {@code StreamMaxLength} must be at least the largest
 * allowed upload ({@code lock.storage.max-video-bytes}), or large files never pass.
 */
@Component
@ConditionalOnProperty(name = "lock.scan.provider", havingValue = "clamav")
public class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;

    private final LockProperties.Scan props;

    public ClamAvScanner(LockProperties props) {
        this.props = props.scan();
    }

    @Override
    public Verdict scan(InputStream bytes) {
        try (Socket socket = new Socket()) {
            int timeout = (int) props.timeout().toMillis();
            socket.connect(new InetSocketAddress(props.clamavHost(), props.clamavPort()), timeout);
            socket.setSoTimeout(timeout);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int n;
            while ((n = bytes.read(buffer)) > 0) {
                out.writeInt(n);
                out.write(buffer, 0, n);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("ClamAV scan failed", e);
        }
    }

    static Verdict parse(String reply) {
        String r = reply.trim();
        if (r.endsWith("OK") && !r.contains("FOUND")) {
            return Verdict.clean();
        }
        if (r.endsWith("FOUND")) {
            String body = r.startsWith("stream:") ? r.substring("stream:".length()) : r;
            return Verdict.infected(body.substring(0, body.length() - "FOUND".length()).trim());
        }
        throw new IllegalStateException("Unexpected ClamAV reply: " + r);
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) { // the reply ends with a NUL byte (z-prefixed command)
            reply.write(b);
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
