package app.lock.media.transcode;

import app.lock.config.LockProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the {@code ffmpeg} binary (roadmap 2.8). Every output is re-encoded from decoded frames and
 * written with {@code -map_metadata -1}, so location and device metadata in the original never
 * reach a rendition (the same rule as photo thumbnails).
 *
 * <p>Inputs are untrusted uploads, already scanned for malware. ffmpeg runs with a timeout, no
 * network protocols ({@code -protocol_whitelist file}), and only on files in a temp directory.
 */
@Component
@ConditionalOnProperty(name = "lock.transcode.provider", havingValue = "ffmpeg")
public class FfmpegTranscoder implements MediaTranscoder {

    private static final Logger log = LoggerFactory.getLogger(FfmpegTranscoder.class);
    private static final int MAX_STDERR = 2000;

    private final String ffmpeg;
    private final Duration timeout;

    public FfmpegTranscoder(LockProperties props) {
        this.ffmpeg = props.transcode().ffmpegPath();
        this.timeout = props.transcode().timeout();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void videoPlayback(Path original, Path out, int maxSide) {
        // Fit inside maxSide x maxSide without upscaling; libx264 needs even dimensions.
        String scale = "scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
                .formatted(maxSide, maxSide);
        run(List.of("-i", original.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-vf", scale,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-profile:v", "high",
                "-c:a", "aac", "-b:a", "128k", "-ac", "2",
                "-movflags", "+faststart", "-map_metadata", "-1", "-f", "mp4", out.toString()));
    }

    @Override
    public void audioPlayback(Path original, Path out) {
        run(List.of("-i", original.toString(), "-vn", "-map", "0:a:0", "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart", "-map_metadata", "-1", "-f", "mp4", out.toString()));
    }

    @Override
    public void videoStill(Path original, Path outPng) {
        try {
            run(List.of("-ss", "1", "-i", original.toString(), "-frames:v", "1", "-map_metadata", "-1", outPng.toString()));
        } catch (TranscodeException e) {
            log.debug("No frame at 1s, using the first: {}", e.getMessage());
        }
        if (!exists(outPng)) {
            run(List.of("-i", original.toString(), "-frames:v", "1", "-map_metadata", "-1", outPng.toString()));
        }
    }

    @Override
    public Optional<Path> decodeImage(Path original, Path outPng) {
        try {
            // ffmpeg reads HEIC as an image sequence of tiles on some builds; take the first frame,
            // which for phone photos is the full primary image.
            run(List.of("-i", original.toString(), "-frames:v", "1", "-map_metadata", "-1", outPng.toString()));
            return exists(outPng) ? Optional.of(outPng) : Optional.empty();
        } catch (TranscodeException e) {
            log.info("ffmpeg could not decode image: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void run(List<String> args) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-protocol_whitelist", "file"));
        command.addAll(args);
        Path stderr = null;
        try {
            stderr = Files.createTempFile("lock-ffmpeg", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(stderr.toFile())
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new TranscodeException("ffmpeg timed out after " + timeout);
            }
            if (process.exitValue() != 0) {
                String err = Files.readString(stderr);
                throw new TranscodeException("ffmpeg exited " + process.exitValue() + ": "
                        + err.substring(0, Math.min(err.length(), MAX_STDERR)).strip());
            }
        } catch (IOException e) {
            throw new TranscodeException("Could not run " + ffmpeg + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscodeException("Interrupted");
        } finally {
            if (stderr != null) {
                try {
                    Files.deleteIfExists(stderr);
                } catch (IOException ignored) {
                    // temp file; the OS cleans it up eventually
                }
            }
        }
    }

    private static boolean exists(Path p) {
        try {
            return Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** ffmpeg failed on this input. Not retried forever: the processor records it and moves on. */
    public static class TranscodeException extends RuntimeException {
        public TranscodeException(String message) {
            super(message);
        }
    }
}
