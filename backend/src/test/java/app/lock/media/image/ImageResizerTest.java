package app.lock.media.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageResizerTest {

    @Test
    void neverUpscales() throws Exception {
        BufferedImage small = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        var r = ImageResizer.resize(small, 1024);
        assertThat(r.width()).isEqualTo(100);
        assertThat(r.height()).isEqualTo(50);
        assertThat(ImageIO.read(new ByteArrayInputStream(r.jpeg()))).isNotNull();
    }

    /** Orientation 6 (phone held upright): the stored pixels are landscape and must be rotated clockwise. */
    @Test
    void appliesExifRotation() {
        BufferedImage landscape = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        landscape.setRGB(0, 0, Color.RED.getRGB()); // top-left
        BufferedImage upright = ImageResizer.orient(landscape, 6);
        assertThat(upright.getWidth()).isEqualTo(20);
        assertThat(upright.getHeight()).isEqualTo(40);
        assertThat(upright.getRGB(19, 0)).isEqualTo(Color.RED.getRGB()); // now top-right
    }

    @Test
    void readsOrientationFromExifAndIgnoresGarbage() throws Exception {
        assertThat(ExifOrientation.read(new byte[] {1, 2, 3})).isEqualTo(1);
        assertThat(ExifOrientation.read(withOrientation(jpeg(), 6))).isEqualTo(6);
        assertThat(ExifOrientation.read(jpeg())).isEqualTo(1);
    }

    @Test
    void derivativesCarryNoExif() throws Exception {
        byte[] withExif = withOrientation(jpeg(), 6);
        var r = ImageResizer.resize(ImageResizer.decode(withExif).orElseThrow(), 256);
        assertThat(new String(r.jpeg(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(r.width()).isLessThan(r.height()); // rotated upright before scaling
    }

    private static byte[] jpeg() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        return out.toByteArray();
    }

    /** Inserts a minimal big-endian EXIF APP1 segment, with one orientation entry, after SOI. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'M', 'M', 0, 42, 0, 0, 0, 8,     // header, IFD at offset 8
                0, 1,                            // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1,    // tag 0x0112, SHORT, count 1
                0, (byte) orientation, 0, 0,     // value
                0, 0, 0, 0};                     // no next IFD
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(exifHeader);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
