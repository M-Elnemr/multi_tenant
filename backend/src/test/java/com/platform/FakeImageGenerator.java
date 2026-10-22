package com.platform;

import com.platform.marketing.ImageGenerator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for the AI image model: always returns a small valid PNG and remembers the prompts. */
@Component
@Primary
public class FakeImageGenerator implements ImageGenerator {
    public static final List<String> PROMPTS = new ArrayList<>();
    public static volatile boolean FAIL = false;
    public static volatile boolean HAD_REFERENCE = false;

    public static synchronized void reset() { PROMPTS.clear(); FAIL = false; HAD_REFERENCE = false; }

    @Override public boolean available() { return true; }

    @Override
    public synchronized Generated generate(String prompt, byte[] reference, String referenceType) {
        PROMPTS.add(prompt);
        HAD_REFERENCE = reference != null && reference.length > 0;
        if (FAIL) throw new ImageException("The AI image service is busy");
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) img.setRGB(x, y, 0x3366CC);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return new Generated(out.toByteArray(), "image/png");
        } catch (java.io.IOException e) {
            throw new ImageException("png", e);
        }
    }
}
