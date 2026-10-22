package com.platform.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Gemini image generation over REST (same key as the assistant; the model name is configurable). The product photo, when given, is sent along as the picture to build on. */
@Component
public class GeminiImageGenerator implements ImageGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String key;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public GeminiImageGenerator(@Value("${app.ai.gemini.api-key:}") String key, @Value("${app.ai.gemini.image-model:gemini-2.5-flash-image}") String model) {
        this.key = key == null || key.isBlank() ? null : key.trim();
        this.model = model;
    }

    @Override public boolean available() { return key != null; }

    @Override
    public Generated generate(String prompt, byte[] reference, String referenceType) {
        if (key == null) throw new ImageException("AI images are not set up yet");
        try {
            ObjectNode body = JSON.createObjectNode();
            var parts = body.putArray("contents").addObject().putArray("parts");
            parts.addObject().put("text", prompt);
            if (reference != null && reference.length > 0 && reference.length < 7_000_000)
                parts.addObject().putObject("inline_data").put("mime_type", referenceType == null ? "image/jpeg" : referenceType).put("data", Base64.getEncoder().encodeToString(reference));
            body.putObject("generationConfig").putArray("responseModalities").add("IMAGE").add("TEXT");
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json").header("x-goog-api-key", key).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429 || res.statusCode() == 503) throw new ImageException("The AI image service is busy, please try again in a moment");
            if (res.statusCode() != 200) throw new ImageException("The AI image service could not make a picture (" + res.statusCode() + ")");
            for (JsonNode p : JSON.readTree(res.body()).path("candidates").path(0).path("content").path("parts")) {
                JsonNode d = p.has("inlineData") ? p.get("inlineData") : p.get("inline_data");
                if (d != null && d.hasNonNull("data")) {
                    String type = d.path("mimeType").asText(d.path("mime_type").asText("image/png"));
                    return new Generated(Base64.getDecoder().decode(d.get("data").asText()), type);
                }
            }
            throw new ImageException("The AI did not return a picture, try a different description");
        } catch (ImageException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageException("The AI image request was interrupted", e);
        } catch (Exception e) {
            throw new ImageException("The AI image service could not be reached", e);
        }
    }
}
