package com.platform.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Langfuse observability (OTLP/HTTP JSON, Langfuse's supported ingestion path; the legacy /api/public/ingestion is deprecated).
 * <p>
 * Guarantees: off unless configured; the reply path only ever does a non-blocking {@code offer} on a bounded queue (a full queue drops the event, never waits);
 * a daemon thread batches and sends with short timeouts; every failure is swallowed (rate-limited log line). Nothing here can slow or fail a customer reply.
 * Privacy: by default only metadata is sent (model, latency, token counts, status, tool names). Tenant and conversation ids are salted SHA-256 hashes, so traces
 * are grouped per shop and per chat without exposing the real ids. Prompt/answer text is sent only when {@code captureContent} is on (use for synthetic shops only),
 * and then it passes through {@link #redact}.
 */
public final class AiTelemetry {
    private static final Logger log = LoggerFactory.getLogger(AiTelemetry.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();

    private static volatile AiTelemetry INSTANCE;
    private static final AtomicLong DROPPED = new AtomicLong(), FAILED = new AtomicLong(), SENT = new AtomicLong();

    private final String endpoint, auth, salt, environment;
    private final boolean captureContent;
    private final BlockingQueue<ObjectNode> queue;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile long lastWarn;

    private AiTelemetry(String host, String pk, String sk, String salt, boolean capture, int queueSize, String environment) {
        this.endpoint = host.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        this.auth = "Basic " + Base64.getEncoder().encodeToString((pk + ":" + sk).getBytes(StandardCharsets.UTF_8));
        this.salt = salt == null ? "" : salt;
        this.captureContent = capture;
        this.environment = environment == null ? "" : environment;
        this.queue = new ArrayBlockingQueue<>(Math.max(10, queueSize));
    }

    /** Starts telemetry when enabled and fully configured; otherwise leaves it off. Returns whether it is on. */
    public static synchronized boolean configure(boolean enabled, String host, String pk, String sk, String salt, boolean capture, int queueSize, String environment) {
        if (INSTANCE != null) INSTANCE = null;
        if (!enabled || blank(host) || blank(pk) || blank(sk)) return false;
        AiTelemetry t = new AiTelemetry(host, pk, sk, salt, capture, queueSize, environment);
        Thread th = new Thread(t::run, "ai-telemetry");
        th.setDaemon(true);
        th.start();
        INSTANCE = t;
        return true;
    }

    public static boolean enabled() { return INSTANCE != null; }

    public static long dropped() { return DROPPED.get(); }

    public static long failed() { return FAILED.get(); }

    public static long sent() { return SENT.get(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    // ---- reply scope (a thread-local trace id, like AiBudget) ------------------------------------------------------------------

    /** A new reply begins on this thread: its model calls and the final timeline become one trace. */
    public static void beginReply(UUID tenantId, UUID conversationId) { beginReply(tenantId, conversationId, null); }

    /** Same, with the id of the reply's {@code ReplyTrace} (32 hex characters) so that a Langfuse trace and the chat.reply_traces row are one record; a missing or malformed id gets a random one as before. */
    public static void beginReply(UUID tenantId, UUID conversationId, String traceId) {
        AiTelemetry t = INSTANCE;
        if (t == null) return;
        TRACE.set(traceId != null && traceId.matches("[0-9a-f]{32}") ? traceId : randomHex(16));
        SESSION.set(t.hash(conversationId) + "|" + t.hash(tenantId));
    }

    /** The trace id of the reply running on this thread, or null (telemetry off, or no reply). */
    static String currentTrace() { return TRACE.get(); }

    /** DR-R: the trace and session of this thread, for a model call that runs on another thread (the early failover). */
    static String[] capture() { return new String[] {TRACE.get(), SESSION.get()}; }

    static void adopt(String[] s) { if (s[0] == null) TRACE.remove(); else TRACE.set(s[0]); if (s[1] == null) SESSION.remove(); else SESSION.set(s[1]); }

    static void release() { TRACE.remove(); SESSION.remove(); }

    /** Ends the reply: one root span with the timeline summary (phases, in ms) and the channel. */
    public static void endReply(String channel, String timelineSummary, long startMillis) {
        try {
            AiTelemetry t = INSTANCE;
            String trace = TRACE.get();
            if (t != null && trace != null) {
                ObjectNode span = t.span(trace, randomHex(8), "customer-reply", "span", startMillis, System.currentTimeMillis(), false, null);
                t.attr(span, "langfuse.trace.name", "customer-reply");
                t.attr(span, "langfuse.observation.metadata.channel", channel);
                t.attr(span, "langfuse.observation.metadata.timeline", timelineSummary == null ? "" : timelineSummary);
                t.offer(span);
            }
        } catch (RuntimeException ignored) { /* telemetry never fails a reply */ }
        finally { TRACE.remove(); SESSION.remove(); }
    }

    /** One model request (a successful call or a failed attempt) of the active reply. Safe to call anywhere; does nothing when off. */
    public static void generation(String modelCode, String modelId, long startMillis, long endMillis, AiProvider.Usage usage, int httpStatus, String error, String promptText, String answerText) {
        try {
            AiTelemetry t = INSTANCE;
            if (t == null) return;
            String trace = TRACE.get();
            if (trace == null) trace = randomHex(16);   // a background call has no reply: its own trace
            boolean failed = error != null;
            ObjectNode span = t.span(trace, randomHex(8), "llm-call:" + modelCode, "generation", startMillis, endMillis, failed, error);
            t.attr(span, "gen_ai.request.model", modelId);
            t.attr(span, "langfuse.observation.metadata.model_code", modelCode);
            t.attr(span, "langfuse.observation.metadata.http_status", httpStatus);
            t.attr(span, "langfuse.observation.metadata.latency_ms", endMillis - startMillis);
            if (usage != null) {
                ObjectNode u = JSON.createObjectNode();
                u.put("input", usage.input()); u.put("output", usage.output());
                if (usage.cached() > 0) u.put("cache_read_input_tokens", usage.cached());
                t.attr(span, "langfuse.observation.usage_details", u.toString());
            }
            if (t.captureContent) {
                if (promptText != null) t.attr(span, "langfuse.observation.input", redact(promptText));
                if (answerText != null) t.attr(span, "langfuse.observation.output", redact(answerText));
            }
            t.offer(span);
        } catch (RuntimeException ignored) { /* see endReply */ }
    }

    /** A named step of the reply (tool call, review, rule check, retrieval) that took {@code ms}. */
    public static void step(String type, String name, long startMillis, long endMillis, boolean failed) {
        try {
            AiTelemetry t = INSTANCE;
            String trace = TRACE.get();
            if (t == null || trace == null) return;
            t.offer(t.span(trace, randomHex(8), name, type, startMillis, endMillis, failed, failed ? "failed" : null));
        } catch (RuntimeException ignored) { /* see endReply */ }
    }

    // ---- redaction ---------------------------------------------------------------------------------------------------------------

    private static final java.util.regex.Pattern PHONE = java.util.regex.Pattern.compile("(?<![\\d])\\+?\\d[\\d\\s-]{8,14}\\d");
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile("(?i)(bearer\\s+[A-Za-z0-9._-]{12,}|sk-[A-Za-z0-9_-]{12,}|api[_-]?key\\W{0,3}[A-Za-z0-9._-]{12,})");

    /** Masks phone numbers, e-mail addresses and key-like tokens, and caps the length. Used only when content capture is on. */
    static String redact(String s) {
        if (s == null) return null;
        String r = SECRET.matcher(s).replaceAll("[secret]");
        r = EMAIL.matcher(r).replaceAll("[email]");
        r = PHONE.matcher(r).replaceAll("[phone]");
        return r.length() > 6000 ? r.substring(0, 6000) + "…" : r;
    }

    // ---- OTLP/JSON ---------------------------------------------------------------------------------------------------------------

    private String hash(UUID id) {
        if (id == null) return "none";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) { return "unhashed"; }
    }

    private ObjectNode span(String trace, String id, String name, String type, long startMs, long endMs, boolean error, String message) {
        ObjectNode s = JSON.createObjectNode();
        s.put("traceId", trace); s.put("spanId", id); s.put("name", name); s.put("kind", 1);
        s.put("startTimeUnixNano", String.valueOf(startMs * 1_000_000L));
        s.put("endTimeUnixNano", String.valueOf(Math.max(endMs, startMs) * 1_000_000L));
        s.putArray("attributes");
        attr(s, "langfuse.observation.type", type);
        String session = SESSION.get();
        if (session != null) {
            String[] p = session.split("\\|");
            attr(s, "langfuse.session.id", p[0]);
            attr(s, "langfuse.trace.metadata.tenant", p.length > 1 ? p[1] : "none");
        }
        if (!environment.isEmpty()) attr(s, "langfuse.environment", environment);
        if (error) {
            attr(s, "langfuse.observation.level", "ERROR");
            attr(s, "langfuse.observation.status_message", message == null ? "error" : message);
            s.putObject("status").put("code", 2).put("message", message == null ? "error" : message);
        }
        return s;
    }

    private void attr(ObjectNode span, String key, Object value) {
        ObjectNode a = ((ArrayNode) span.get("attributes")).addObject();
        a.put("key", key);
        ObjectNode v = a.putObject("value");
        if (value instanceof Long l) v.put("intValue", String.valueOf(l));
        else if (value instanceof Integer i) v.put("intValue", String.valueOf(i));
        else v.put("stringValue", String.valueOf(value));
    }

    private void offer(ObjectNode span) {
        if (!queue.offer(span)) DROPPED.incrementAndGet();   // never wait: a full queue loses the event, not the reply
    }

    /** The OTLP request body for these spans. */
    static ObjectNode envelope(List<ObjectNode> spans) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode rs = root.putArray("resourceSpans").addObject();
        ArrayNode ra = rs.putObject("resource").putArray("attributes");
        ObjectNode name = ra.addObject(); name.put("key", "service.name"); name.putObject("value").put("stringValue", "elmanassa-backend");
        ArrayNode arr = rs.putArray("scopeSpans").addObject().putArray("spans");
        spans.forEach(arr::add);
        return root;
    }

    private void run() {
        List<ObjectNode> batch = new ArrayList<>();
        while (true) {
            try {
                ObjectNode first = queue.poll(2, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, 99);
                send(batch);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (RuntimeException e) { warn(e.toString()); }
            finally { batch.clear(); }
        }
    }

    private void send(List<ObjectNode> batch) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", auth).header("Content-Type", "application/json").header("x-langfuse-ingestion-version", "4")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope(batch).toString())).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 == 2) SENT.addAndGet(batch.size());
            else { FAILED.addAndGet(batch.size()); warn("HTTP " + res.statusCode()); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { FAILED.addAndGet(batch.size()); warn(e.toString()); }
    }

    private void warn(String m) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) { lastWarn = now; log.warn("Langfuse telemetry not delivered (this line is rate-limited): {}", m); }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
