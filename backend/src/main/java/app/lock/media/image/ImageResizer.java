package app.lock.media.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Downscales photos to JPEG derivatives with the JDK's ImageIO (roadmap 2.1), so no native library is
 * needed. Re-encoding from pixels writes no metadata, so EXIF (including GPS) never reaches a
 * derivative; the EXIF orientation is applied first, so portrait phone photos are not sideways.
 *
 * <p>ImageIO reads JPEG, PNG, GIF and BMP. HEIC/HEIF and WebP are not supported here: {@link #decode}
 * returns empty and the media worker converts them with the transcoder, when one is configured (F7).
 */
public final class ImageResizer {

    private static final float JPEG_QUALITY = 0.82f;

    private ImageResizer() {}

    public record Rendition(byte[] jpeg, int width, int height) {}

    /** Decodes and orients an image, or returns empty if the format is not supported. */
    public static Optional<BufferedImage> decode(byte[] original) {
        BufferedImage image;
        try {
            image = ImageIO.read(new java.io.ByteArrayInputStream(original));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // corrupt, or a format ImageIO cannot read
        }
        if (image == null) {
            return Optional.empty();
        }
        return Optional.of(orient(image, ExifOrientation.read(original)));
    }

    /** Scales so the longest side is at most {@code maxSide}. Never upscales. */
    public static Rendition resize(BufferedImage source, int maxSide) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
        int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
        // TYPE_INT_RGB drops any alpha channel: JPEG has none, and transparent PNG areas become white.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return new Rendition(encodeJpeg(out), w, h);
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param); // null metadata: nothing copied over
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Applies an EXIF orientation (1–8) so the pixels are upright. */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        // Maps source (x, y) to upright output; arguments are (m00, m10, m01, m11, m02, m12).
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);  // mirror horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);  // mirror vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);   // transpose
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);  // rotate 90 clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // transverse
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8: rotate 90 counter-clockwise
        };
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
