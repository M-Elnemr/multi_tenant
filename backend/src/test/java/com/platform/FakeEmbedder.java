package com.platform;

import com.platform.chat.Embedder;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Stands in for the embedding provider in tests: letter trigrams and words hashed into a vector, so texts that share spelling are close («جاكت» and «جاكيت», «سماعة رياضة» and
 * «سماعة رقبة رياضية»). Off unless a test turns it on, so the other tests search by words exactly as before.
 */
@Component
@Primary
public class FakeEmbedder implements Embedder {
    public static volatile boolean on = false;
    public static volatile int calls = 0;

    @Override public boolean enabled() { return on; }

    @Override public String model() { return "fake-trigram@256"; }

    @Override
    public float[][] embed(List<String> texts) {
        calls++;
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) out[i] = vector(texts.get(i));
        return out;
    }

    private static String norm(String t) {
        return t.toLowerCase().replaceAll("[أإآ]", "ا").replace('ة', 'ه').replace('ى', 'ي').replaceAll("[^\\p{L}\\p{N}\\s]", " ");
    }

    static float[] vector(String text) {
        float[] v = new float[256];
        for (String w : norm(text).split("\\s+")) {
            if (w.isBlank()) continue;
            v[Math.floorMod(w.hashCode(), 256)] += 2;
            String p = " " + w + " ";
            for (int k = 0; k + 3 <= p.length(); k++) v[Math.floorMod(p.substring(k, k + 3).hashCode(), 256)] += 1;
        }
        double s = 0;
        for (float x : v) s += x * x;
        if (s > 0) for (int k = 0; k < v.length; k++) v[k] /= (float) Math.sqrt(s);
        return v;
    }
}
