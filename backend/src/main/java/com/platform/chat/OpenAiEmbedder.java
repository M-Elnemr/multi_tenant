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
    /** A customer's query waits at most {@link #QUERY_TIMEOUT_MS} in all, so connecting gets a short limit of its own (the indexing client above is patient). */
    private final HttpClient queryHttp = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1_500)).build();
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

    /** After the provider refuses (a key not allowed the model, an account on hold) or fails, embeddings rest a while: searches go by words without waiting on a call that fails.
     */
    private volatile long restUntil = 0;

    @Override public boolean enabled() { return !key.isEmpty() && System.currentTimeMillis() >= restUntil; }

    @Override public boolean configured() { return !key.isEmpty(); }

    // ---- what happened, for the AI health page and the ops alert ------------------------------------------------------------------------
    private final java.util.concurrent.atomic.AtomicLong queries = new java.util.concurrent.atomic.AtomicLong(), queryOk = new java.util.concurrent.atomic.AtomicLong(),
            queryFailed = new java.util.concurrent.atomic.AtomicLong(), recoveredByRetry = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger streak = new java.util.concurrent.atomic.AtomicInteger();   // failed queries in a row
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> failuresByKind = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<long[]> recent = new java.util.concurrent.ConcurrentLinkedDeque<>();   // {finished at, millis, ok(1/0)} of the last queries
    private volatile String lastError = "";
    private volatile long lastErrorAt = 0, lastOkAt = 0;

    private void note(boolean ok, long millis, String kind, String error) {
        recent.addLast(new long[]{System.currentTimeMillis(), millis, ok ? 1 : 0});
        while (recent.size() > 300) recent.pollFirst();
        if (ok) { queryOk.incrementAndGet(); streak.set(0); lastOkAt = System.currentTimeMillis(); return; }
        queryFailed.incrementAndGet();
        failuresByKind.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        lastError = error == null ? "" : error;
        lastErrorAt = System.currentTimeMillis();
    }

    @Override
    public Map<String, Object> health() {
        List<long[]> r = new ArrayList<>(recent);
        long since = System.currentTimeMillis() - 15 * 60_000L;
        List<long[]> window = r.stream().filter(x -> x[0] >= since).toList();
        List<Long> ok = r.stream().filter(x -> x[2] == 1).map(x -> x[1]).sorted().toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured()); m.put("enabled", enabled()); m.put("model", model());
        m.put("restingSeconds", Math.max(0, (restUntil - System.currentTimeMillis()) / 1000));
        m.put("failedInARow", streak.get());
        m.put("queries", queries.get()); m.put("queriesOk", queryOk.get()); m.put("queriesFailed", queryFailed.get()); m.put("recoveredByRetry", recoveredByRetry.get());
        Map<String, Long> kinds = new LinkedHashMap<>();
        failuresByKind.forEach((k, v) -> kinds.put(k, v.get()));
        m.put("failuresByKind", kinds);
        m.put("last15MinQueries", window.size()); m.put("last15MinFailed", window.stream().filter(x -> x[2] == 0).count());
        m.put("latencyMs", ok.isEmpty() ? Map.of() : Map.of("avg", ok.stream().mapToLong(Long::longValue).sum() / ok.size(), "median", ok.get(ok.size() / 2), "p95", ok.get(Math.min(ok.size() - 1, (int) Math.ceil(ok.size() * 0.95) - 1)), "max", ok.get(ok.size() - 1)));
        m.put("lastError", lastError); m.put("lastErrorAt", lastErrorAt == 0 ? null : java.time.Instant.ofEpochMilli(lastErrorAt).toString()); m.put("lastOkAt", lastOkAt == 0 ? null : java.time.Instant.ofEpochMilli(lastOkAt).toString());
        return m;
    }

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

    /** The longest a customer waits for their words to be embedded; Alibaba usually answers one short text in well under a second. */
    static final long QUERY_TIMEOUT_MS = 3_500;

    /**
     * A customer's words while they wait: at most {@link #QUERY_TIMEOUT_MS} in all and never more than the reply has left, with one quick retry inside that time (a connect that
     * timed out or a 429 / 5xx usually works the second time; two failures in a row cost the customer only the wait). One failed query does not switch search by meaning off for
     * everyone: only {@link #REST_AFTER_FAILED_QUERIES} failed queries in a row do, for a growing time (20 s, 40 s ... 5 min), and any success starts over. A refusal (a key not allowed,
     * an account on hold) rests 10 minutes at once. Every outcome is counted for the health page.
     */
    @Override
    public float[] embedQuery(String text) {
        if (!enabled()) throw new IllegalStateException("Embeddings are off (no key or resting)");
        long started = System.currentTimeMillis();
        long deadline = started + Math.min(QUERY_TIMEOUT_MS, AiBudget.remainingMs() - 3_000);
        if (deadline - started < 700) throw new IllegalStateException("No time left in this reply for search by meaning");
        queries.incrementAndGet();
        String kind = "error", error = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            long left = deadline - System.currentTimeMillis();
            if (left < 600) break;
            try {
                HttpResponse<String> res = queryHttp.send(request(List.of(text), left), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) {
                    float[] v = parse(JSON.readTree(res.body()), 1).get(0);
                    note(true, System.currentTimeMillis() - started, "", null);
                    if (attempt > 0) recoveredByRetry.incrementAndGet();
                    return v;
                }
                error = "Embeddings HTTP " + res.statusCode() + ": " + (res.body().length() > 200 ? res.body().substring(0, 200) : res.body());
                if (res.statusCode() != 429 && res.statusCode() < 500) {   // refused: a retry cannot help and neither will the next replies
                    restUntil = System.currentTimeMillis() + 600_000;
                    note(false, System.currentTimeMillis() - started, "refused-" + res.statusCode(), error);
                    throw new IllegalStateException(error);
                }
                kind = "http-" + res.statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } catch (java.io.IOException e) {   // timed out or unreachable
                kind = e instanceof java.net.http.HttpConnectTimeoutException ? "connect-timeout" : e instanceof java.net.http.HttpTimeoutException ? "timeout" : "io";
                error = "Embeddings slow or unreachable: " + e.getMessage();
            }
            if (attempt == 0 && deadline - System.currentTimeMillis() > 900) {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        note(false, System.currentTimeMillis() - started, kind, error);
        int inARow = streak.incrementAndGet();
        if (inARow >= REST_AFTER_FAILED_QUERIES) restUntil = System.currentTimeMillis() + Math.min(300_000L, 20_000L << Math.min(inARow - REST_AFTER_FAILED_QUERIES, 4));
        throw new IllegalStateException(error.isEmpty() ? "Embeddings did not answer in time" : error);
    }

    /** Failed queries in a row (each already retried once) before search by meaning rests for everyone. */
    static final int REST_AFTER_FAILED_QUERIES = 3;

    private HttpRequest request(List<String> part, long timeoutMs) throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", part.stream().map(t -> t.length() > 6000 ? t.substring(0, 6000) : t).toList());
        body.put("dimensions", dims);
        body.put("encoding_format", "float");
        return HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings")).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }

    private List<float[]> call(List<String> part) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(request(part, 40_000), HttpResponse.BodyHandlers.ofString());
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
