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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * OpenAI over its Chat Completions API (default model: gpt-6-luna). Chosen with {@code AI_PROVIDER=openai}. GPT-6 Luna only accepts function calling on this endpoint when
 * reasoning is switched off, so the request always carries {@code reasoning_effort: none}: a shop assistant needs speed and low cost, not long reasoning.
 * The key travels in a header, never in a URL.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
public class OpenAiProvider implements AiProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String key, model, effort, baseUrl;
    private final int maxOutput;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public OpenAiProvider(@Value("${app.ai.openai.api-key:}") String key, @Value("${app.ai.openai.model:gpt-6-luna}") String model,
                          @Value("${app.ai.openai.reasoning-effort:none}") String effort, @Value("${app.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
                          @Value("${app.ai.openai.max-output-tokens:1500}") int maxOutput) {
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model.trim();
        this.effort = effort == null ? "" : effort.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.maxOutput = maxOutput;
    }

    @Override public boolean available() { return key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (key == null) throw new AiUnavailableException("The AI assistant is not configured yet");
        String body;
        try { body = JSON.writeValueAsString(requestBody(system, turns, tools)); } catch (Exception e) { throw new AiUnavailableException("The AI assistant could not be reached", e); }
        AiUnavailableException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {   // one retry for a busy or failing service
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofSeconds(40))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return parse(JSON.readTree(res.body()));
                last = new AiUnavailableException("The AI assistant is busy, try again (HTTP " + res.statusCode() + ")");
                if (res.statusCode() != 429 && res.statusCode() / 100 != 5) break;
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
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system == null ? "" : system));
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
        if (!effort.isEmpty()) body.put("reasoning_effort", effort);
        body.put("max_completion_tokens", maxOutput);
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
        if (!calls.isEmpty()) raw.set("tool_calls", (ArrayNode) msg.path("tool_calls"));
        return new Turn("model", text, calls, List.of(), raw);
    }
}
