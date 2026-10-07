package com.platform.files;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.platform.shared.BusinessException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Makes an uploaded picture safe and efficient before it is published:
 *  - checks it really decodes as the declared format and is not a decompression bomb (pixel cap),
 *  - applies the camera's rotation, then re-encodes, which drops EXIF/GPS and any appended payload,
 *  - produces a bounded "original" plus "medium" and "thumb" variants (JPEG for photos, PNG when transparency matters).
 * At most two images are decoded at once so a burst of uploads cannot exhaust memory.
 */
@Component
public class ImageProcessor {
    public record Variant(String name, byte[] data, int width, int height, String ext, String contentType) {}

    static final long MAX_PIXELS = 25_000_000L;
    private static final int[][] SIZES = {{2000, 0}, {800, 1}, {320, 2}};
    private static final String[] NAMES = {"original", "medium", "thumb"};
    private final Semaphore gate = new Semaphore(2);

    public List<Variant> process(byte[] raw, String declaredType) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Upload interrupted");
        }
        try {
            BufferedImage src = decode(raw, declaredType);
            src = applyOrientation(src, orientation(raw));
            boolean alpha = src.getColorModel().hasAlpha() && "image/png".equals(declaredType);
            List<Variant> out = new ArrayList<>();
            for (int i = 0; i < SIZES.length; i++) {
                BufferedImage scaled = scaleDown(src, SIZES[i][0], alpha);
                out.add(encode(NAMES[i], scaled, alpha));
            }
            return out;
        } finally {
            gate.release();
        }
    }

    private BufferedImage decode(byte[] raw, String declaredType) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw notAnImage();
            ImageReader reader = readers.next();
            try {
                String fmt = reader.getFormatName().toLowerCase();
                boolean jpeg = fmt.contains("jpeg") || fmt.contains("jpg");
                if (!(jpeg && "image/jpeg".equals(declaredType)) && !(fmt.equals("png") && "image/png".equals(declaredType))) throw notAnImage();
                reader.setInput(in, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w * h > MAX_PIXELS) throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Image is too large (max 25 megapixels)");
                BufferedImage img = reader.read(0);
                if (img == null) throw notAnImage();
                return img;
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw notAnImage();
        }
    }

    private static BusinessException notAnImage() { return BusinessException.badRequest("FILE_NOT_ALLOWED", "File content does not match its type"); }

    private static int orientation(byte[] raw) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(raw));
            ExifIFD0Directory d = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            return d != null && d.containsTag(ExifIFD0Directory.TAG_ORIENTATION) ? d.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** EXIF orientation 1-8 -> upright pixels (otherwise phone photos appear sideways once the EXIF is stripped). */
    static BufferedImage applyOrientation(BufferedImage img, int o) {
        if (o <= 1 || o > 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        boolean swap = o >= 5;
        AffineTransform t = new AffineTransform();
        switch (o) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage src, int maxSide, boolean alpha) {
        int w = src.getWidth(), h = src.getHeight();
        double f = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * f)), nh = Math.max(1, (int) Math.round(h * f));
        BufferedImage out = new BufferedImage(nw, nh, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (!alpha) { g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, nw, nh); }
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static Variant encode(String name, BufferedImage img, boolean png) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName(png ? "png" : "jpeg").next();
            try (MemoryCacheImageOutputStream os = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(os);
                ImageWriteParam p = writer.getDefaultWriteParam();
                if (!png) {
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(0.85f);
                    p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
                }
                writer.write(null, new IIOImage(img, null, null), p);   // no metadata passed: EXIF/GPS/comments are not carried over
            } finally {
                writer.dispose();
            }
            return new Variant(name, bos.toByteArray(), img.getWidth(), img.getHeight(), png ? "png" : "jpg", png ? "image/png" : "image/jpeg");
        } catch (Exception e) {
            throw BusinessException.badRequest("FILE_NOT_ALLOWED", "Could not process this image");
        }
    }
}
