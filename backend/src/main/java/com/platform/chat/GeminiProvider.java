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
    private final String key;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public GeminiProvider(@Value("${app.ai.gemini.api-key:}") String key, @Value("${app.ai.gemini.model:gemini-flash-lite-latest}") String model) {
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model;
    }

    @Override public boolean available() { return key != null; }

    @Override
    public Turn next(String system, List<Turn> turns, List<ToolDef> tools) {
        if (key == null) throw new AiUnavailableException("The AI assistant is not configured yet");
        try {
            ObjectNode body = JSON.createObjectNode();
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
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

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("x-goog-api-key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException first) {   // a slow or dropped connection: one more try
                res = http.send(req, HttpResponse.BodyHandlers.ofString());
            }
            if (res.statusCode() == 429) throw new AiUnavailableException("The AI assistant is busy, please try again in a moment");
            if (res.statusCode() != 200) throw new AiUnavailableException("The AI assistant could not answer (" + res.statusCode() + ")");
            JsonNode parts = JSON.readTree(res.body()).path("candidates").path(0).path("content").path("parts");
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode p : parts) {
                if (p.hasNonNull("text") && !p.path("thought").asBoolean(false)) text.append(p.get("text").asText());
                if (p.has("functionCall")) {
                    Map<String, Object> args = p.path("functionCall").has("args") ? JSON.convertValue(p.path("functionCall").get("args"), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {}) : Map.of();
                    calls.add(new ToolCall(p.path("functionCall").path("name").asText(), args));
                }
            }
            return new Turn("model", text.toString().trim(), calls, List.of(), parts.isMissingNode() ? null : parts);
        } catch (AiUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("The AI assistant was interrupted", e);
        } catch (Exception e) {
            throw new AiUnavailableException("The AI assistant could not be reached", e);
        }
    }
}
