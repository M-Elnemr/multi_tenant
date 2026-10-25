package com.platform.chat;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Google Gemini over its REST API (free tier first; only the key and model change when upgrading). The key travels in a header, never in a URL. */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiProvider implements AiProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeminiProvider.class);
    private final String key;
    private final List<String> models;
    private final long hedgeMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "gemini"); t.setDaemon(true); return t; });

    /**
     * {@code models} is the list to try, fastest first: Google's free tier is often overloaded for some models (503 or 30 s answers) while another
     * answers in a second. A model that fails answers the next one at once, and one that is merely slow is joined by the next after {@code hedgeMs}.
     */
    public GeminiProvider(@Value("${app.ai.gemini.api-key:}") String key, @Value("${app.ai.gemini.models:gemini-3-flash-preview,gemini-flash-lite-latest,gemini-3.1-flash-lite}") String models,
                          @Value("${app.ai.gemini.hedge-ms:10000}") long hedgeMs) {
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.models = java.util.Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList();
        this.hedgeMs = hedgeMs;
    }

    @Override public String code() { return "gemini"; }

    @Override public String label() { return "Gemini (" + (models.isEmpty() ? "?" : models.get(0)) + ")"; }

    @Override public boolean available() { return key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (key == null) throw new AiUnavailableException("The AI assistant is not configured yet");
        final String body;
        try { body = JSON.writeValueAsString(requestBody(system, turns, tools)); } catch (Exception e) { throw new AiUnavailableException("The AI assistant could not be reached", e); }
        java.util.concurrent.ExecutorCompletionService<Turn> done = new java.util.concurrent.ExecutorCompletionService<>(pool);
        List<java.util.concurrent.Future<Turn>> running = new java.util.ArrayList<>();
        int next = 0, failures = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        try {
            running.add(done.submit(() -> call(models.get(0), body)));
            next = 1;
            AiUnavailableException last = null;
            while (System.nanoTime() < deadline) {
                java.util.concurrent.Future<Turn> f = done.poll(next < models.size() ? hedgeMs : 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) {   // nobody answered yet: bring in the next model alongside
                    if (next < models.size()) { final String m = models.get(next++); log.info("Gemini slow after {} ms: also asking {}", hedgeMs, m); running.add(done.submit(() -> call(m, body))); }
                    continue;
                }
                try {
                    return f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures++;
                    last = e.getCause() instanceof AiUnavailableException u ? u : new AiUnavailableException("The AI assistant could not be reached", e.getCause());
                    if (next < models.size()) { final String m = models.get(next++); running.add(done.submit(() -> call(m, body))); }
                    else if (failures >= running.size()) throw last;
                }
            }
            throw last != null ? last : new AiUnavailableException("The AI assistant is busy, please try again in a moment");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("The AI assistant was interrupted", e);
        } finally {
            for (var f : running) f.cancel(true);   // the slower ones are no longer needed
        }
    }

    private ObjectNode requestBody(String system, List<Turn> turns, List<ToolDef> tools) {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", AiProvider.flat(system));
        ArrayNode contents = body.putArray("contents");
        for (Turn t : turns) {
            ObjectNode c = contents.addObject();
            if (t.raw() instanceof JsonNode parts && "model".equals(t.role())) {
                c.put("role", "model");
                c.set("parts", parts);
            } else if ("tool".equals(t.role())) {
                c.put("role", "user");
                ArrayNode parts = c.putArray("parts");
                for (ToolResult r : t.results()) {
                    ObjectNode fr = parts.addObject().putObject("functionResponse");
                    fr.put("name", r.name());
                    fr.set("response", JSON.valueToTree(r.result()));
                }
            } else {
                c.put("role", "user".equals(t.role()) ? "user" : "model");
                c.putArray("parts").addObject().put("text", t.text() == null ? "" : t.text());
            }
        }
        if (!tools.isEmpty()) {
            ArrayNode decl = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolDef d : tools) {
                ObjectNode f = decl.addObject();
                f.put("name", d.name());
                f.put("description", d.description());
                f.set("parameters", JSON.valueToTree(d.parameters()));
            }
        }
        body.putObject("generationConfig").put("temperature", 0.5).put("maxOutputTokens", 900);
        return body;
    }

    /** One model, one request. Tries minimal "thinking" first (a shop assistant needs speed, not long reasoning) and repeats without it if the model refuses the setting. */
    private Turn call(String model, String bodyJson) {
        try {
            AiUnavailableException failure = null;
            for (boolean thinking : new boolean[] {true, false}) {
                JsonNode root = JSON.readTree(bodyJson);
                if (thinking) ((ObjectNode) root.get("generationConfig")).putObject("thinkingConfig").put("thinkingLevel", "minimal");
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                        .timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json").header("x-goog-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(root))).build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 400 && thinking) continue;   // this model does not take the thinking setting
                if (res.statusCode() == 429 || res.statusCode() == 503) throw new AiUnavailableException("The AI assistant is busy (" + model + " " + res.statusCode() + ")");
                if (res.statusCode() != 200) throw new AiUnavailableException("The AI assistant could not answer (" + model + " " + res.statusCode() + ")");
                return parse(res.body());
            }
            throw failure != null ? failure : new AiUnavailableException("The AI assistant could not answer (" + model + ")");
        } catch (AiUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("The AI assistant was interrupted", e);
        } catch (Exception e) {
            throw new AiUnavailableException("The AI assistant could not be reached (" + model + ")", e);
        }
    }

    private Turn parse(String responseBody) throws java.io.IOException {
        JsonNode root = JSON.readTree(responseBody);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        JsonNode um = root.path("usageMetadata");
        Usage usage = new Usage(um.path("promptTokenCount").asInt(0), um.path("candidatesTokenCount").asInt(0) + um.path("thoughtsTokenCount").asInt(0), um.path("cachedContentTokenCount").asInt(0));
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode p : parts) {
            if (p.hasNonNull("text") && !p.path("thought").asBoolean(false)) text.append(p.get("text").asText());
            if (p.has("functionCall")) {
                Map<String, Object> args = p.path("functionCall").has("args") ? JSON.convertValue(p.path("functionCall").get("args"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {}) : Map.of();
                calls.add(new ToolCall(p.path("functionCall").path("name").asText(), args));
            }
        }
        if (text.length() == 0 && calls.isEmpty()) throw new AiUnavailableException("The AI assistant returned nothing");
        return new Turn("model", text.toString().trim(), calls, List.of(), parts.isMissingNode() ? null : parts, usage);
    }
}
