package com.platform.chat;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Claude over the Messages API (default model: claude-haiku-5-5). Chosen with {@code AI_PROVIDER=anthropic}.
 * The part of the prompt before {@link AiProvider#CACHE_BREAK} (tools + rules + shop facts) is marked for the 1-hour prompt cache, because a shop gets a chat every few minutes
 * and the default 5-minute cache would expire in between; the per-customer part follows uncached. Thinking stays adaptive at low effort (Haiku 5.5 cannot be sampled or forced),
 * and the model's own content blocks (thinking included) are replayed unchanged inside a tool exchange. The key travels in a header only.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "anthropic")
public class AnthropicProvider implements AiProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String key, model, effort, baseUrl, cacheTtl;
    private final int maxOutput;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public AnthropicProvider(@Value("${app.ai.anthropic.api-key:}") String key, @Value("${app.ai.anthropic.model:claude-haiku-5-5}") String model,
                             @Value("${app.ai.anthropic.effort:low}") String effort, @Value("${app.ai.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl,
                             @Value("${app.ai.anthropic.cache-ttl:1h}") String cacheTtl, @Value("${app.ai.anthropic.max-output-tokens:1500}") int maxOutput) {
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model.trim();
        this.effort = effort == null ? "" : effort.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.cacheTtl = cacheTtl == null ? "" : cacheTtl.trim();
        this.maxOutput = maxOutput;
    }

    @Override public String code() { return "anthropic"; }

    @Override public String label() { return "Claude (" + model + ")"; }

    @Override public boolean available() { return key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (key == null) throw new AiUnavailableException("The AI assistant is not configured yet");
        String body;
        try { body = JSON.writeValueAsString(requestBody(system, turns, tools)); } catch (Exception e) { throw new AiUnavailableException("The AI assistant could not be reached", e); }
        AiUnavailableException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/messages")).timeout(Duration.ofSeconds(40))
                        .header("x-api-key", key).header("anthropic-version", "2023-06-01").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return parse(JSON.readTree(res.body()));
                last = new AiUnavailableException("The AI assistant is busy, try again (HTTP " + res.statusCode() + ")");
                if (res.statusCode() != 429 && res.statusCode() != 529 && res.statusCode() / 100 != 5) break;
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("The AI assistant was interrupted", e);
            } catch (AiUnavailableException e) {
                throw e;
            } catch (Exception e) {
                last = new AiUnavailableException("The AI assistant could not be reached", e);
            }
        }
        throw last != null ? last : new AiUnavailableException("The AI assistant could not be reached");
    }

    // ---- request ---------------------------------------------------------------------------------------------------------------

    Map<String, Object> requestBody(String system, List<Turn> turns, List<ToolDef> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxOutput);
        String sys = system == null ? "" : system;
        int cut = sys.indexOf(CACHE_BREAK);
        List<Object> blocks = new ArrayList<>();
        String stable = cut < 0 ? sys : sys.substring(0, cut);
        String volatileText = cut < 0 ? "" : sys.substring(cut + CACHE_BREAK.length());
        Map<String, Object> first = new LinkedHashMap<>(Map.of("type", "text", "text", stable));
        if (!cacheTtl.isEmpty()) first.put("cache_control", "5m".equals(cacheTtl) ? Map.of("type", "ephemeral") : Map.of("type", "ephemeral", "ttl", cacheTtl));
        blocks.add(first);
        if (!volatileText.isBlank()) blocks.add(Map.of("type", "text", "text", volatileText));
        body.put("system", blocks);
        if (tools != null && !tools.isEmpty()) {
            List<Object> defs = new ArrayList<>();
            for (ToolDef d : tools) defs.add(Map.of("name", d.name(), "description", d.description(), "input_schema", OpenAiProvider.lowerTypes(d.parameters())));
            body.put("tools", defs);
        }
        body.put("thinking", Map.of("type", "adaptive"));
        if (!effort.isEmpty()) body.put("output_config", Map.of("effort", effort));

        List<Object> messages = new ArrayList<>();
        JsonNode lastCalls = null;   // tool_use blocks of the previous model turn: their ids pair with the results that follow
        for (Turn t : turns) {
            if ("user".equals(t.role())) {
                messages.add(Map.of("role", "user", "content", t.text() == null ? "" : t.text()));
            } else if ("model".equals(t.role())) {
                if (t.raw() instanceof JsonNode raw) {
                    messages.add(Map.of("role", "assistant", "content", JSON.convertValue(raw, new TypeReference<List<Object>>() {})));
                    lastCalls = raw;
                } else {
                    messages.add(Map.of("role", "assistant", "content", t.text() == null || t.text().isBlank() ? "." : t.text()));
                    lastCalls = null;
                }
            } else if ("tool".equals(t.role())) {
                List<Object> results = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                if (lastCalls != null) for (JsonNode b : lastCalls) if ("tool_use".equals(b.path("type").asText())) ids.add(b.path("id").asText());
                int i = 0;
                for (ToolResult r : t.results()) {
                    results.add(Map.of("type", "tool_result", "tool_use_id", i < ids.size() ? ids.get(i) : "toolu_" + i, "content", toJson(r.result())));
                    i++;
                }
                messages.add(Map.of("role", "user", "content", results));
            }
        }
        body.put("messages", merge(messages));
        return body;
    }

    /** The API wants roles to alternate: join consecutive same-role string messages (e.g. two customer messages sent in a row). */
    @SuppressWarnings("unchecked")
    private static List<Object> merge(List<Object> in) {
        List<Object> out = new ArrayList<>();
        for (Object o : in) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (!out.isEmpty()) {
                Map<String, Object> prev = (Map<String, Object>) out.get(out.size() - 1);
                if (prev.get("role").equals(m.get("role")) && prev.get("content") instanceof String a && m.get("content") instanceof String b) {
                    out.set(out.size() - 1, Map.of("role", m.get("role"), "content", a + "\n" + b));
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }

    private static String toJson(Object o) { try { return JSON.writeValueAsString(o); } catch (Exception e) { return "{}"; } }

    // ---- response --------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    Turn parse(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) throw new AiUnavailableException("The AI assistant returned nothing");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode b : content) {
            switch (b.path("type").asText()) {
                case "text" -> text.append(b.path("text").asText(""));
                case "tool_use" -> calls.add(new ToolCall(b.path("name").asText(), b.path("input").isObject() ? JSON.convertValue(b.path("input"), Map.class) : Map.of()));
                default -> { }   // thinking blocks stay in raw so the next request replays them unchanged
            }
        }
        JsonNode u = root.path("usage");
        int cached = u.path("cache_read_input_tokens").asInt(0);
        int written = u.path("cache_creation_input_tokens").asInt(0);
        int input = u.path("input_tokens").asInt(0) + cached + written;
        return new Turn("model", text.toString().trim(), calls, List.of(), content, new Usage(input, u.path("output_tokens").asInt(0), cached, written));
    }
}
