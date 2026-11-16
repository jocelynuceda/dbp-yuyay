package com.yuyay.attachment.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Locale;

/**
 * Pixel dimensions of an uploaded image, for diagnostics only.
 * <p>
 * Only the image header is read (no full decode), so a 200-megapixel photo does not allocate its pixels.
 */
public record ImageSize(int width, int height) {

    /**
     * Returns the dimensions of a JPEG or PNG, or null for any other content type, an unreadable header,
     * a missing reader or non-positive values. Never throws.
     */
    public static ImageSize read(byte[] content, String contentType) {
        if (content == null || contentType == null) return null;
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        if (!type.equals("image/jpeg") && !type.equals("image/png")) return null;
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (stream == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) return null;
                return new ImageSize(width, height);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
