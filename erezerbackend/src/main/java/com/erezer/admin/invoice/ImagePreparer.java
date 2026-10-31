package com.erezer.admin.invoice;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Normalises uploaded photos for embedding in a PDF: applies the EXIF orientation that phones rely on,
 * flattens transparency onto white, caps the resolution and re-encodes as JPEG. WebP decoding comes from
 * the TwelveMonkeys ImageIO plugin (registered automatically via SPI).
 */
@Component
class ImagePreparer {

    private static final Logger log = LoggerFactory.getLogger(ImagePreparer.class);

    /** Long edge in pixels; ~220 dpi for a half-width A4 slot, plenty for print. */
    static final int MAX_EDGE = 1600;
    private static final float JPEG_QUALITY = 0.85f;

    /** @return JPEG bytes and pixel size, or empty when the image cannot be decoded */
    Optional<PreparedImage> prepare(byte[] original) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                return Optional.empty();
            }
            image = applyOrientation(image, orientation(original));
            image = flattenAndScale(image);
            return Optional.of(new PreparedImage(encodeJpeg(image), image.getWidth(), image.getHeight(), null));
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not prepare image for the invoice", ex);
            return Optional.empty();
        }
    }

    private static int orientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ex) {
            // No or unreadable metadata: treat as upright.
        }
        return 1;
    }

    /** EXIF orientations 1-8 as defined by the TIFF spec. */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    private static BufferedImage flattenAndScale(BufferedImage src) {
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** @param caption printed under the image on the invoice; may be null */
    record PreparedImage(byte[] jpeg, int width, int height, String caption) {

        PreparedImage withCaption(String text) {
            return new PreparedImage(jpeg, width, height, text);
        }
    }
}
