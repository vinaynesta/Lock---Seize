package app.lock.media.transcode;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Native media work the JDK cannot do (roadmap 2.8, F7): playback renditions of video and audio,
 * a still frame from a video, and decoding image formats ImageIO cannot read (HEIC/HEIF, WebP).
 * Implementations: {@link FfmpegTranscoder}, and {@link NoopTranscoder} when none is configured.
 *
 * <p>Files, not byte arrays: a 2 GB video must never be held in memory.
 */
public interface MediaTranscoder {

    boolean isEnabled();

    /** H.264/AAC MP4, longest side at most {@code maxSide}, fast-start for streaming. */
    void videoPlayback(Path original, Path out, int maxSide);

    /** AAC in MP4: plays everywhere, unlike the WAV, OGG or WebM a phone may record. */
    void audioPlayback(Path original, Path out);

    /** A PNG still from about a second into the video (or its first frame if shorter). */
    void videoStill(Path original, Path outPng);

    /** Converts an image ImageIO cannot read (HEIC, HEIF, WebP) to PNG. Empty if it can't be decoded either. */
    Optional<Path> decodeImage(Path original, Path outPng);
}
