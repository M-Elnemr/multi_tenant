package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embeddings over the OpenAI-compatible /embeddings endpoint (Alibaba Model Studio qwen3.7-text-embedding by default; Gemini, OpenAI and others speak it too). Texts go in batches of
 * {@code batch} (Alibaba takes 10), with a short retry on a rate limit or a server error. Vectors come back unit length, so a dot product is the cosine.
 */
@Component
class OpenAiEmbedder implements Embedder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final String key, baseUrl, model;
    private final int dims, batch;

    OpenAiEmbedder(@Value("${app.ai.embed.api-key:}") String key, @Value("${app.ai.embed.base-url:https://dashscope-intl.aliyuncs.com/compatible-mode/v1}") String baseUrl,
                   @Value("${app.ai.embed.model:qwen3.7-text-embedding}") String model, @Value("${app.ai.embed.dimensions:512}") int dims, @Value("${app.ai.embed.batch:10}") int batch) {
        this.key = key == null ? "" : key.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.dims = dims;
        this.batch = Math.max(1, batch);
    }

    /** After the provider refuses (a key not allowed the model, an account on hold) or fails, embeddings rest a while: searches go by words without waiting on a call that fails. */
    private volatile long restUntil = 0;

    @Override public boolean enabled() { return !key.isEmpty() && System.currentTimeMillis() >= restUntil; }

    @Override public String model() { return model + "@" + dims; }

    @Override
    public float[][] embed(List<String> texts) {
        if (!enabled()) throw new IllegalStateException("Embeddings are off (no key)");
        float[][] out = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += batch) {
            List<String> part = texts.subList(from, Math.min(texts.size(), from + batch));
            List<float[]> got = call(part);
            for (int i = 0; i < got.size(); i++) out[from + i] = got.get(i);
        }
        return out;
    }

    private List<float[]> call(List<String> part) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", part.stream().map(t -> t.length() > 6000 ? t.substring(0, 6000) : t).toList());
        body.put("dimensions", dims);
        body.put("encoding_format", "float");
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings")).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return parse(JSON.readTree(res.body()), part.size());
                last = new IllegalStateException("Embeddings HTTP " + res.statusCode() + ": " + (res.body().length() > 200 ? res.body().substring(0, 200) : res.body()));
                if (res.statusCode() != 429 && res.statusCode() < 500) { restUntil = System.currentTimeMillis() + 600_000; break; }   // a refused key or model will not fix itself soon: rest 10 minutes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } catch (java.io.IOException e) {
                last = new IllegalStateException("Embeddings unreachable: " + e.getMessage(), e);
            }
            try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (restUntil < System.currentTimeMillis()) restUntil = System.currentTimeMillis() + 60_000;   // down or busy: words only for a minute
        throw last;
    }

    private static List<float[]> parse(JsonNode root, int n) {
        float[][] out = new float[n][];
        for (JsonNode d : root.path("data")) {
            int i = d.path("index").asInt(-1);
            JsonNode e = d.path("embedding");
            if (i < 0 || i >= n || !e.isArray()) continue;
            float[] v = new float[e.size()];
            for (int k = 0; k < v.length; k++) v[k] = (float) e.get(k).asDouble();
            out[i] = unit(v);
        }
        List<float[]> list = new ArrayList<>();
        for (float[] v : out) { if (v == null) throw new IllegalStateException("Embeddings: a vector is missing"); list.add(v); }
        return list;
    }

    static float[] unit(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        if (s == 0) return v;
        float k = (float) (1 / Math.sqrt(s));
        for (int i = 0; i < v.length; i++) v[i] *= k;
        return v;
    }
}
