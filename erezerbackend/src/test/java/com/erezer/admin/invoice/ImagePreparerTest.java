package com.erezer.admin.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImagePreparerTest {

    private final ImagePreparer preparer = new ImagePreparer();

    @Test
    void downscalesLargePhotosToTheMaximumEdgeAndOutputsJpeg() throws IOException {
        ImagePreparer.PreparedImage prepared = preparer.prepare(png(4000, 3000, false)).orElseThrow();
        assertThat(prepared.width()).isEqualTo(ImagePreparer.MAX_EDGE);
        assertThat(prepared.height()).isEqualTo(1200);
        assertThat(prepared.jpeg()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(prepared.jpeg()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void flattensTransparencyOntoWhite() throws IOException {
        ImagePreparer.PreparedImage prepared = preparer.prepare(png(10, 10, true)).orElseThrow();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(prepared.jpeg()));
        Color pixel = new Color(decoded.getRGB(5, 5));
        assertThat(pixel.getRed()).isGreaterThan(240);
        assertThat(pixel.getGreen()).isGreaterThan(240);
    }

    @Test
    void appliesExifRotationSwappingDimensions() {
        BufferedImage landscape = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        landscape.setRGB(0, 0, Color.RED.getRGB()); // top-left marker
        BufferedImage rotated = ImagePreparer.applyOrientation(landscape, 6); // rotate 90° clockwise
        assertThat(rotated.getWidth()).isEqualTo(20);
        assertThat(rotated.getHeight()).isEqualTo(40);
        assertThat(new Color(rotated.getRGB(19, 0), true).getRed()).isEqualTo(255); // now top-right
    }

    @Test
    void rejectsBytesThatAreNotAnImage() {
        assertThat(preparer.prepare("not an image".getBytes())).isEmpty();
    }

    private static byte[] png(int width, int height, boolean transparent) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (!transparent) {
            for (int x = 0; x < width; x += 50) {
                image.setRGB(x, 0, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
