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

    /**
     * Same idea for JPEG, the format phone cameras use: a real JPEG whose frame header is rewritten to
     * 65 000 x 50 000 pixels and cut right after the scan header, so there is no pixel data at all.
     */
    @Test
    void readsOnlyTheHeaderOfAHugeJpeg() throws IOException {
        assertEquals(new ImageSize(65_000, 50_000), ImageSize.read(jpegHeaderOnly(65_000, 50_000), "image/jpeg"));
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

    /** Keeps the tables and headers of a real JPEG, declares other dimensions and drops the scan data. */
    private static byte[] jpegHeaderOnly(int width, int height) throws IOException {
        byte[] jpeg = encode("jpg", 16, 16);
        int frame = marker(jpeg, 0xC0);
        int scan = marker(jpeg, 0xDA);
        ByteBuffer.wrap(jpeg, frame + 5, 4).putShort((short) height).putShort((short) width);
        int scanHeaderLength = ((jpeg[scan + 2] & 0xFF) << 8) | (jpeg[scan + 3] & 0xFF);
        return Arrays.copyOf(jpeg, scan + 2 + scanHeaderLength);
    }

    private static int marker(byte[] jpeg, int code) {
        for (int i = 2; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == code) return i;
        }
        throw new IllegalStateException("JPEG marker not found: " + Integer.toHexString(code));
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
