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
    private final String code, name, key, model, effort, baseUrl, extraBody;
    private final boolean maxTokensField;
    private final int maxOutput;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /**
     * Under its own model code. {@code maxTokensField} sends the limit as {@code max_tokens}
     * (what those services read) instead of {@code max_completion_tokens}; {@code extraBody} is a JSON object merged into every request, e.g. a setting that switches thinking off.
     */
    public OpenAiProvider(String code, String name, String key, String model, String effort, String baseUrl, int maxOutput, boolean maxTokensField, String extraBody) {
        this.code = code;
        this.name = name;
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model.trim();
        this.effort = effort == null ? "" : effort.trim();
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.maxOutput = maxOutput;
        this.maxTokensField = maxTokensField;
        this.extraBody = extraBody == null ? "" : extraBody.trim();
    }

    @Override public String code() { return code; }

    @Override public String label() { return name + " (" + model + ")"; }

    @Override public boolean available() { return key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (key == null) throw new AiUnavailableException("The AI assistant is not configured yet");
        String body;
        try { body = JSON.writeValueAsString(requestBody(system, turns, tools)); } catch (Exception e) { throw new AiUnavailableException("The AI assistant could not be reached", e); }
        AiUnavailableException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {   // two retries, with a growing pause, for a busy (429) or failing service
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(Duration.ofSeconds(40))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) return parse(JSON.readTree(res.body()));
                last = new AiUnavailableException("The AI assistant is busy, try again (HTTP " + res.statusCode() + ")");
                if (res.statusCode() != 429 && res.statusCode() / 100 != 5) break;
                Thread.sleep(attempt == 0 ? 1000 : 2500);
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
        if (!effort.isEmpty()) body.put("reasoning_effort", effort);
        body.put(maxTokensField ? "max_tokens" : "max_completion_tokens", maxOutput);
        if (!extraBody.isEmpty()) {
            try { body.putAll(JSON.readValue(extraBody, new TypeReference<Map<String, Object>>() {})); } catch (Exception e) { throw new AiUnavailableException("The extra request settings of " + code + " are not valid JSON"); }
        }
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
        JsonNode u = root.path("usage");
        // OpenAI reports the cached part under prompt_tokens_details, DeepSeek as prompt_cache_hit_tokens: both are part of prompt_tokens
        int cached = Math.max(u.path("prompt_tokens_details").path("cached_tokens").asInt(0), u.path("prompt_cache_hit_tokens").asInt(0));
        return new Turn("model", text, calls, List.of(), raw, new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), cached));
    }
}
