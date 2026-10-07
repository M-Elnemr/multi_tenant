package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.files.ImageProcessor;
import com.platform.shared.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {
    final ImageProcessor processor = new ImageProcessor();

    static byte[] png(int w, int h, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(alpha ? new Color(255, 0, 0, 120) : Color.BLUE);
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", o);
        return o.toByteArray();
    }

    /** Inserts an APP1/EXIF segment (orientation + a GPS-style marker string) right after the JPEG SOI, like a phone camera does. */
    static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] tiff = {
            'M', 'M', 0, 42, 0, 0, 0, 8,         // big-endian TIFF header, IFD0 at offset 8
            0, 1,                                // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // tag 0x0112 Orientation, SHORT, count 1
            0, 0, 0, 0 };                        // no next IFD
        byte[] marker = "GPS-SECRET-LOCATION".getBytes(StandardCharsets.US_ASCII);
        byte[] id = {'E', 'x', 'i', 'f', 0, 0};
        int len = 2 + id.length + tiff.length + marker.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE1); o.write(len >> 8); o.write(len & 0xFF);
        o.writeBytes(id); o.writeBytes(tiff); o.writeBytes(marker);
        o.write(jpeg, 2, jpeg.length - 2);
        return o.toByteArray();
    }

    static BufferedImage decode(byte[] b) throws Exception { return ImageIO.read(new ByteArrayInputStream(b)); }

    @Test
    void producesBoundedVariantsAndNeverUpscales() throws Exception {
        List<ImageProcessor.Variant> v = processor.process(jpeg(3000, 1500), "image/jpeg");
        assertThat(v).extracting(ImageProcessor.Variant::name).containsExactly("original", "medium", "thumb");
        assertThat(v.get(0).width()).isEqualTo(2000);
        assertThat(v.get(1).width()).isEqualTo(800);
        assertThat(v.get(2).width()).isEqualTo(320);
        assertThat(v.get(2).height()).isEqualTo(160);   // aspect kept
        List<ImageProcessor.Variant> small = processor.process(jpeg(100, 50), "image/jpeg");
        assertThat(small.get(0).width()).isEqualTo(100);   // small images stay as they are
        assertThat(small.get(2).width()).isEqualTo(100);
        for (var x : v) assertThat(decode(x.data())).isNotNull();
    }

    @Test
    void stripsExifGpsAndAppliesCameraRotation() throws Exception {
        byte[] raw = withExif(jpeg(400, 200), 6);   // orientation 6 = rotate 90 degrees clockwise to display
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).contains("GPS-SECRET-LOCATION");
        var out = processor.process(raw, "image/jpeg");
        for (var x : out) {
            assertThat(new String(x.data(), StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET-LOCATION").doesNotContain("Exif");
        }
        assertThat(out.get(0).width()).isEqualTo(200);    // landscape 400x200 became upright portrait 200x400
        assertThat(out.get(0).height()).isEqualTo(400);
    }

    @Test
    void keepsTransparencyForPngAndRejectsMismatchesGarbageAndBombs() throws Exception {
        var v = processor.process(png(200, 100, true), "image/png");
        assertThat(v.get(0).ext()).isEqualTo("png");
        assertThat(decode(v.get(0).data()).getColorModel().hasAlpha()).isTrue();

        assertThatThrownBy(() -> processor.process(jpeg(100, 100), "image/png")).isInstanceOf(BusinessException.class);      // declared type lies
        byte[] fake = new byte[200];
        fake[0] = (byte) 0x89; fake[1] = 'P'; fake[2] = 'N'; fake[3] = 'G';
        assertThatThrownBy(() -> processor.process(fake, "image/png")).isInstanceOf(BusinessException.class);               // right magic, not an image
        BufferedImage big = new BufferedImage(6000, 5000, BufferedImage.TYPE_BYTE_GRAY);   // 30 megapixels, tiny when compressed
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(big, "png", o);
        assertThat(o.size()).isLessThan(1_000_000);
        assertThatThrownBy(() -> processor.process(o.toByteArray(), "image/png")).isInstanceOf(BusinessException.class)
                .hasMessageContaining("too large");
    }
}
