package com.platform.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Any service that speaks the OpenAI Chat Completions protocol: here GLM through Hive and Qwen through Alibaba Cloud. Function calling needs the reasoning setting chosen per model (GLM: "low"), so it is a constructor argument.
 * The key travels in a header, never in a URL.
 */
public class OpenAiProvider implements AiProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String code, name, key, model, baseUrl, extraBody;
    private final java.util.function.Supplier<String> effortSetting;
    /** The Hive keys the requests are spread over (null: this provider has the one {@code key}). */
    private final HiveKeyPool pool;
    private final boolean maxTokensField;
    private final int maxOutput, maxPerSecond;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** One model call never waits longer than this for an answer; a reply has several calls and the gateway gives up at 60 seconds. */
    static final long CALL_TIMEOUT_MS = 20_000;

    /** Time a hedged call keeps after the threshold: the first call is not cut off before the twin has had this long to answer. */
    static final long HEDGE_ROOM_MS = 12_000;

    /** How long one call may go unanswered: {@link #CALL_TIMEOUT_MS}, or the hedge threshold plus {@link #HEDGE_ROOM_MS} when that is longer (otherwise a threshold above the timeout could never fire). */
    static long callTimeoutMs() { return Math.max(CALL_TIMEOUT_MS, HEDGE_MS + HEDGE_ROOM_MS); }

    /** Calls to one service (by address and key) share one limit, however many providers point at it: Hive allows GLM 5 calls a second for the whole account. */
    private static final Map<String, Throttle> THROTTLES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Under its own model code. {@code maxTokensField} sends the limit as {@code max_tokens}
     * (what those services read) instead of {@code max_completion_tokens}; {@code extraBody} is a JSON object merged into every request, e.g. a setting that switches thinking off.
     */
    public OpenAiProvider(String code, String name, String key, String model, String effort, String baseUrl, int maxOutput, boolean maxTokensField, String extraBody) {
        this(code, name, key, model, effort, baseUrl, maxOutput, maxTokensField, extraBody, 0);
    }

    /** Same, starting at most {@code maxPerSecond} calls a second to this service (0 = no limit): a burst waits a moment instead of being refused with 429. */
    public OpenAiProvider(String code, String name, String key, String model, String effort, String baseUrl, int maxOutput, boolean maxTokensField, String extraBody, int maxPerSecond) {
        this(code, name, key, null, model, () -> effort, baseUrl, maxOutput, maxTokensField, extraBody, maxPerSecond);
    }

    /** A model served by Hive: its requests go over every key of the pool in turn, and {@code effort} is read for each request (the admin can change it without a restart). */
    public OpenAiProvider(String code, String name, HiveKeyPool pool, String model, java.util.function.Supplier<String> effort, String baseUrl, int maxOutput, boolean maxTokensField, String extraBody, int maxPerSecond) {
        this(code, name, null, pool, model, effort, baseUrl, maxOutput, maxTokensField, extraBody, maxPerSecond);
    }

    private OpenAiProvider(String code, String name, String key, HiveKeyPool pool, String model, java.util.function.Supplier<String> effort, String baseUrl, int maxOutput, boolean maxTokensField, String extraBody, int maxPerSecond) {
        this.code = code;
        this.name = name;
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.pool = pool;
        this.model = model.trim();
        this.effortSetting = effort == null ? () -> "" : effort;
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.maxOutput = maxOutput;
        this.maxTokensField = maxTokensField;
        this.extraBody = extraBody == null ? "" : extraBody.trim();
        this.maxPerSecond = maxPerSecond;
    }

    /** The reasoning setting for this request. */
    private String effort() { String e = effortSetting.get(); return e == null ? "" : e.trim(); }

    /** Calls to one service with one key share one limit (Hive: about 5 a second per key), however many providers point at it. */
    private Throttle throttleFor(String key) {
        return maxPerSecond > 0 ? THROTTLES.computeIfAbsent(baseUrl + "|" + (key == null ? "" : Integer.toHexString(key.hashCode())), k -> new Throttle(maxPerSecond)) : null;
    }

    private HiveKeyPool.Lease lease(String excludeId) {
        if (pool != null) return pool.acquire(excludeId);
        return key == null ? null : new HiveKeyPool.Lease("fixed", key);
    }

    /** At most {@code max} calls started in any one second; a caller over it waits (never longer than 3 seconds, then goes anyway and lets the service decide). */
    static final class Throttle {
        private final int max;
        private final java.util.ArrayDeque<Long> started = new java.util.ArrayDeque<>();

        Throttle(int max) { this.max = max; }

        void acquire() throws InterruptedException {
            long giveUp = System.nanoTime() + 3_000_000_000L;
            while (true) {
                long wait;
                synchronized (this) {
                    long now = System.nanoTime();
                    while (!started.isEmpty() && now - started.peekFirst() >= 1_000_000_000L) started.pollFirst();
                    if (started.size() < max || now >= giveUp) { started.addLast(now); return; }
                    wait = (started.peekFirst() + 1_000_000_000L - now) / 1_000_000 + 1;
                }
                Thread.sleep(Math.max(1, Math.min(wait, 250)));
            }
        }
    }

    @Override
    public java.util.Map<String, Object> settings() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code); m.put("label", name); m.put("modelId", model); m.put("host", java.net.URI.create(baseUrl).getHost()); m.put("reasoning", effort().isEmpty() ? "(none sent)" : effort());
        m.put("maxOutputTokens", maxOutput); m.put("extraRequestSettings", extraBody.isEmpty() ? "(none)" : extraBody);
        return m;
    }

    @Override public String code() { return code; }

    @Override public String label() { return name + " (" + model + ")"; }

    @Override public boolean available() { return pool != null ? pool.hasKeys() : key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (!available()) throw new AiUnavailableException("The AI assistant is not configured yet");
        String body;
        try { body = JSON.writeValueAsString(requestBody(system, turns, tools)); } catch (Exception e) { throw new AiUnavailableException("The AI assistant could not be reached", e); }
        AiUnavailableException last = null;
        HiveKeyPool.Lease used = null;
        for (int attempt = 0; attempt < 3; attempt++) {   // two retries, with a growing pause, for a busy (429) or failing service, while the reply still has time for them
            if (attempt > 0 && !AiBudget.hasLeft(8_000)) break;
            HiveKeyPool.Lease lease = lease(used == null ? null : used.id());   // a retry goes on another key when the pool has one
            if (lease == null) throw new AiUnavailableException("The AI assistant is not configured yet");
            used = lease;
            try {
                Throttle throttle = throttleFor(lease.key());
                if (throttle != null) throttle.acquire();
                long timeout = Math.max(3_000, Math.min(callTimeoutMs(), AiBudget.remainingMs() - 1_000));
                HttpResponse<String> res = send(body, lease, timeout);
                if (res.statusCode() / 100 == 2) { if (pool != null) pool.ok(lease); return parse(JSON.readTree(res.body())); }
                if (pool != null) pool.failed(lease, res.statusCode());
                ChatService.evalHttp(res.statusCode());
                last = new AiUnavailableException("The AI assistant is busy, try again (HTTP " + res.statusCode() + ")");
                if (res.statusCode() != 429 && res.statusCode() != 408 && res.statusCode() / 100 != 5) break;   // 408: Hive's "busy, try again" (seen live 2026-11-03)
                Thread.sleep(attempt == 0 ? 1000 : 2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("The AI assistant was interrupted", e);
            } catch (AiUnavailableException e) {
                throw e;
            } catch (Exception e) {
                if (pool != null) pool.failed(lease, 0);
                ChatService.evalHttp(0);   // no answer at all (timeout, connection)
                last = new AiUnavailableException("The AI assistant could not be reached", e);
            }
        }
        throw last != null ? last : new AiUnavailableException("The AI assistant could not be reached");
    }

    private HttpRequest request(String body, HiveKeyPool.Lease lease, long timeoutMs) {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + lease.key()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /**
     * How long a call may go unanswered while a customer waits before the same request is sent a second time. Most GLM calls answer in 3-5 seconds, but now and then one hangs
     * until the 20-second timeout and is retried: measured on 2026-11-03, a reply of ONE call took 35-40 seconds that way. The twin usually answers in seconds; the first good
     * answer wins and the other is dropped. Background work (no reply budget) is never doubled. The default is 25 seconds (AI_HEDGE_MS): the twin is sent only when the first call is
     * still pending after that long. For that the call itself must be allowed to last longer than the threshold, see {@link #callTimeoutMs()}.
     */
    static final long DEFAULT_HEDGE_MS = 25_000;
    static volatile long HEDGE_MS = DEFAULT_HEDGE_MS;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiProvider.class);

    private HttpResponse<String> send(String body, HiveKeyPool.Lease lease, long timeoutMs) throws Exception {
        HttpRequest req = request(body, lease, timeoutMs);
        boolean waiting = AiBudget.remainingMs() != Long.MAX_VALUE;
        if (!waiting || timeoutMs < HEDGE_MS + 3_000) return http.send(req, HttpResponse.BodyHandlers.ofString());
        var first = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        try {
            return unwrap(() -> first.get(HEDGE_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException slow) {
            log.info("{} call unanswered after {} ms: sending it a second time", code, HEDGE_MS);
            ChatService.evalHedge();
        }
        if (!AiBudget.takeHedge()) {   // this reply has used its one twin: wait for the first call to the end of its time
            try { return unwrap(() -> first.get(Math.max(1_000, timeoutMs - HEDGE_MS + 1_000), java.util.concurrent.TimeUnit.MILLISECONDS)); }
            catch (Exception noAnswer) { first.cancel(true); throw noAnswer; }   // gave up on it: do not leave it running behind the retry
        }
        HiveKeyPool.Lease other = pool != null ? pool.acquire(lease.id()) : lease;   // the twin goes over another key when there is one: it is not held back by the first key's limit
        Throttle throttle = throttleFor(other.key());
        if (throttle != null) throttle.acquire();
        var second = http.sendAsync(other == lease ? req : request(body, other, timeoutMs), HttpResponse.BodyHandlers.ofString());
        var winner = new java.util.concurrent.CompletableFuture<HttpResponse<String>>();
        var finished = new java.util.concurrent.atomic.AtomicInteger();
        var won = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.UUID tenant = AiBudget.tenant();   // the other thread has no reply budget
        java.util.function.Function<HiveKeyPool.Lease, java.util.function.BiConsumer<HttpResponse<String>, Throwable>> doneFor = l -> (r, ex) -> {
            if (pool != null && (ex != null || r.statusCode() / 100 != 2)) pool.failed(l, ex != null ? 0 : r.statusCode());
            if (ex == null && r.statusCode() / 100 == 2) {
                if (won.compareAndSet(false, true)) winner.complete(r);   // the first good answer
                else lostTwin(tenant, r);   // the slower twin also answered: the service billed it, so it is recorded
            } else if (finished.incrementAndGet() == 2) { if (ex != null) winner.completeExceptionally(ex); else winner.complete(r); }   // both failed: the last failure
        };
        first.whenComplete(doneFor.apply(lease));
        second.whenComplete(doneFor.apply(other));
        try {
            return unwrap(() -> winner.get(Math.max(1_000, timeoutMs - HEDGE_MS + 1_000), java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (Exception noAnswer) {
            first.cancel(true);   // nothing answered in time: stop both
            second.cancel(true);
            throw noAnswer;
        }
        // The losing twin is NOT cancelled when the other one won: cancelling closes our side only, the service keeps generating (and bills) either way, so we let it finish
        // (its own request timeout ends it) and record what it used instead of silently under-counting the cost.
    }

    /** Where the tokens of a hedge's losing twin are recorded (set by AiUsageService). */
    interface LostTwinSink { void lost(java.util.UUID tenantId, String modelCode, Usage usage); }

    static volatile LostTwinSink LOST_TWIN_SINK;

    private void lostTwin(java.util.UUID tenant, HttpResponse<String> res) {
        try {
            JsonNode u = JSON.readTree(res.body()).path("usage");
            int cached = Math.max(u.path("prompt_tokens_details").path("cached_tokens").asInt(0), u.path("prompt_cache_hit_tokens").asInt(0));
            LostTwinSink sink = LOST_TWIN_SINK;
            if (sink != null) sink.lost(tenant, code, new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), cached));
        } catch (Exception e) { log.warn("{} twin answer could not be read: {}", code, e.toString()); }
    }

    private interface Get<T> { T get() throws Exception; }

    /** The failure inside a future, as itself (a timeout or an I/O error), so the caller's retry treats it like a plain call's. */
    private static <T> T unwrap(Get<T> g) throws Exception {
        try { return g.get(); }
        catch (java.util.concurrent.ExecutionException e) { throw e.getCause() instanceof Exception x ? x : e; }
    }

    // ---- request ---------------------------------------------------------------------------------------------------------------

    Map<String, Object> requestBody(String system, List<Turn> turns, List<ToolDef> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", AiProvider.flat(system)));
        JsonNode lastCalls = null;   // the tool calls of the previous model turn: their ids pair with the results that follow
        for (Turn t : turns) {
            if ("user".equals(t.role())) {
                messages.add(Map.of("role", "user", "content", t.text() == null ? "" : t.text()));
            } else if ("model".equals(t.role())) {
                if (t.raw() instanceof JsonNode raw) { messages.add(JSON.convertValue(raw, new TypeReference<Map<String, Object>>() {})); lastCalls = raw.path("tool_calls"); }
                else {
                    messages.add(Map.of("role", "assistant", "content", t.text() == null ? "" : t.text()));
                    lastCalls = null;
                }
            } else if ("tool".equals(t.role())) {
                int i = 0;
                for (ToolResult r : t.results()) {
                    String id = lastCalls != null && lastCalls.size() > i ? lastCalls.get(i).path("id").asText("call_" + i) : "call_" + i;
                    messages.add(Map.of("role", "tool", "tool_call_id", id, "content", toJson(r.result())));
                    i++;
                }
            }
        }
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            List<Object> defs = new ArrayList<>();
            for (ToolDef d : tools) defs.add(Map.of("type", "function", "function", Map.of("name", d.name(), "description", d.description(), "parameters", lowerTypes(d.parameters()))));
            body.put("tools", defs);
        }
        if (!effort().isEmpty()) body.put("reasoning_effort", effort());
        body.put(maxTokensField ? "max_tokens" : "max_completion_tokens", maxOutput);
        if (!extraBody.isEmpty()) {
            try { body.putAll(JSON.readValue(extraBody, new TypeReference<Map<String, Object>>() {})); } catch (Exception e) { throw new AiUnavailableException("The extra request settings of " + code + " are not valid JSON"); }
        }
        Map<String, Object> evalOverride = ChatService.evalOverrides(code);   // the reasoning setting an evaluation tests for this model
        if (evalOverride != null) body.putAll(evalOverride);
        Double evalTemperature = ChatService.evalTemperature();   // an evaluation runs every compared model with the same temperature
        if (evalTemperature != null) body.put("temperature", evalTemperature);
        Integer evalMax = ChatService.evalMaxOutput();   // ... and the same output cap
        if (evalMax != null) body.put(maxTokensField ? "max_tokens" : "max_completion_tokens", evalMax);
        ChatService.evalRequest(code, body);
        return body;
    }

    /** Our tool definitions use upper-case type names (OBJECT, STRING); JSON schema wants lower case. */
    @SuppressWarnings("unchecked")
    static Object lowerTypes(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) m).entrySet()) out.put(e.getKey(), "type".equals(e.getKey()) && e.getValue() instanceof String s ? s.toLowerCase() : lowerTypes(e.getValue()));
            return out;
        }
        if (o instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(lowerTypes(x)); return out; }
        return o;
    }

    private static String toJson(Object o) { try { return JSON.writeValueAsString(o); } catch (Exception e) { return "{}"; } }

    // ---- response --------------------------------------------------------------------------------------------------------------

    Turn parse(JsonNode root) {
        ChatService.evalResponse(root);
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.isMissingNode()) throw new AiUnavailableException("The AI assistant returned nothing");
        String text = msg.path("content").isNull() ? "" : msg.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            Map<String, Object> args = Map.of();
            try { args = JSON.readValue(c.path("function").path("arguments").asText("{}"), new TypeReference<Map<String, Object>>() {}); } catch (Exception ignored) { /* a call with unreadable arguments runs with none and the tool says what is missing */ }
            calls.add(new ToolCall(c.path("function").path("name").asText(), args));
        }
        ObjectNode raw = JSON.createObjectNode();   // replayed as the assistant message on the next step
        raw.put("role", "assistant");
        raw.put("content", text);
        if (!calls.isEmpty()) {
            ArrayNode sent = ((ArrayNode) msg.path("tool_calls")).deepCopy();
            for (int i = 0; i < sent.size(); i++) if (sent.get(i) instanceof ObjectNode c && c.path("id").asText("").isBlank()) c.put("id", "call_" + i);   // the tool result is matched to its call by this id
            raw.set("tool_calls", sent);
            // DeepSeek's thinking mode: the thinking of a step that called tools must go back with the tool results, or the service refuses (400) or loses the thread
            for (String f : new String[] {"reasoning_content", "reasoning"}) if (msg.path(f).isTextual() && !msg.path(f).asText().isBlank()) raw.put(f, msg.path(f).asText());
        }
        JsonNode u = root.path("usage");
        // OpenAI reports the cached part under prompt_tokens_details, DeepSeek as prompt_cache_hit_tokens: both are part of prompt_tokens
        int cached = Math.max(u.path("prompt_tokens_details").path("cached_tokens").asInt(0), u.path("prompt_cache_hit_tokens").asInt(0));
        return new Turn("model", text, calls, List.of(), raw, new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), cached));
    }
}
