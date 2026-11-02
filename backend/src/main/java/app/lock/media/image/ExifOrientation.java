package app.lock.media.image;

/**
 * Reads the EXIF orientation tag (0x0112) from a JPEG without a metadata library. Returns 1 (upright)
 * when the file is not a JPEG, has no EXIF block, or the block is malformed.
 */
final class ExifOrientation {

    private static final int TAG_ORIENTATION = 0x0112;

    private ExifOrientation() {}

    static int read(byte[] jpeg) {
        try {
            return find(jpeg);
        } catch (RuntimeException malformed) {
            return 1;
        }
    }

    private static int find(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return 1; // not a JPEG
        }
        int i = 2;
        while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            int length = u16(b, i + 2, false);
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // start of image data: no EXIF before it
            }
            // APP1 holding "Exif\0\0" followed by a TIFF header.
            if (marker == 0xE1 && length >= 8 && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                return fromTiff(b, i + 10);
            }
            i += 2 + length;
        }
        return 1;
    }

    private static int fromTiff(byte[] b, int tiff) {
        boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
        int ifd = tiff + u32(b, tiff + 4, little);
        int entries = u16(b, ifd, little);
        for (int e = 0; e < entries; e++) {
            int entry = ifd + 2 + e * 12;
            if (u16(b, entry, little) == TAG_ORIENTATION) {
                int value = u16(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int x = b[at] & 0xFF;
        int y = b[at + 1] & 0xFF;
        return little ? (y << 8) | x : (x << 8) | y;
    }

    private static int u32(byte[] b, int at, boolean little) {
        return little
                ? (u16(b, at + 2, true) << 16) | u16(b, at, true)
                : (u16(b, at, false) << 16) | u16(b, at + 2, false);
    }
}
