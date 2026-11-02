package app.lock.media.transcode;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No transcoder configured: originals are served as uploaded, and HEIC photos get no thumbnail. */
@Component
@ConditionalOnProperty(name = "lock.transcode.provider", havingValue = "none", matchIfMissing = true)
public class NoopTranscoder implements MediaTranscoder {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void videoPlayback(Path original, Path out, int maxSide) {
        throw new UnsupportedOperationException("No transcoder configured");
    }

    @Override
    public void audioPlayback(Path original, Path out) {
        throw new UnsupportedOperationException("No transcoder configured");
    }

    @Override
    public void videoStill(Path original, Path outPng) {
        throw new UnsupportedOperationException("No transcoder configured");
    }

    @Override
    public Optional<Path> decodeImage(Path original, Path outPng) {
        return Optional.empty();
    }
}
