package com.yuyay.attachment.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageSizeTest {

    @Test
    void readsTheSizeOfRealJpegAndPngImages() throws IOException {
        assertEquals(new ImageSize(640, 480), ImageSize.read(encode("jpg", 640, 480), "image/jpeg"));
        assertEquals(new ImageSize(30, 20), ImageSize.read(encode("png", 30, 20), "image/png"));
    }

    /**
     * The PNG declares 20 000 x 15 000 pixels but carries no image data: reading it fully would fail or
     * allocate 1.2 GB, so a correct answer proves that only the header is read.
     */
    @Test
    void readsOnlyTheHeaderOfAHugeImage() {
        assertEquals(new ImageSize(20_000, 15_000), ImageSize.read(pngHeaderOnly(20_000, 15_000), "image/png"));
    }

    @Test
    void returnsNullForOtherTypesOrUnreadableContent() throws IOException {
        byte[] jpeg = encode("jpg", 64, 48);
        assertNull(ImageSize.read("%PDF-1.7\n%fake".getBytes(StandardCharsets.US_ASCII), "application/pdf"));
        assertNull(ImageSize.read(jpeg, "application/pdf"));
        assertNull(ImageSize.read(jpeg, "audio/mp4"));
        assertNull(ImageSize.read(jpeg, null));
        assertNull(ImageSize.read(null, "image/jpeg"));
        assertNull(ImageSize.read(new byte[0], "image/jpeg"));
        assertNull(ImageSize.read(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3}, "image/jpeg"));
        assertNull(ImageSize.read(Arrays.copyOf(jpeg, 4), "image/jpeg"));
    }

    @Test
    void theContentTypeIsComparedWithoutCaseOrSurroundingSpaces() throws IOException {
        assertEquals(new ImageSize(64, 48), ImageSize.read(encode("jpg", 64, 48), " IMAGE/JPEG "));
    }

    private static byte[] encode(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IllegalStateException("No writer for " + format);
        return out.toByteArray();
    }

    /** PNG signature plus a valid IHDR chunk and nothing else. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        return png.array();
    }
}
