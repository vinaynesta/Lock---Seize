package app.lock.media;

import app.lock.media.transcode.MediaTranscoder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Stand-in for ffmpeg in tests: "transcodes" by writing a marker, and "decodes" images and video
 * stills as a small PNG. Inputs starting with {@code CORRUPT} fail, like a broken file would.
 */
public class FakeTranscoder implements MediaTranscoder {

    public final List<String> calls = new ArrayList<>();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void videoPlayback(Path original, Path out, int maxSide) {
        calls.add("video:" + maxSide);
        write(original, out, "mp4-video");
    }

    @Override
    public void audioPlayback(Path original, Path out) {
        calls.add("audio");
        write(original, out, "mp4-audio");
    }

    @Override
    public void videoStill(Path original, Path outPng) {
        calls.add("still");
        failIfCorrupt(original);
        png(outPng, 640, 360);
    }

    @Override
    public Optional<Path> decodeImage(Path original, Path outPng) {
        calls.add("decode");
        if (isCorrupt(original)) {
            return Optional.empty();
        }
        png(outPng, 3024, 4032);
        return Optional.of(outPng);
    }

    private static void write(Path original, Path out, String marker) {
        failIfCorrupt(original);
        try {
            Files.writeString(out, marker, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void png(Path out, int w, int h) {
        try {
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void failIfCorrupt(Path original) {
        if (isCorrupt(original)) {
            throw new IllegalStateException("ffmpeg exited 1: Invalid data found when processing input");
        }
    }

    private static boolean isCorrupt(Path original) {
        try {
            return Files.readString(original, StandardCharsets.ISO_8859_1).startsWith("CORRUPT");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
