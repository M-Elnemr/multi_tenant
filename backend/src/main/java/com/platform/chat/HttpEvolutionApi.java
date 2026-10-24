package com.platform.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Talks to Evolution API inside our own network with the server-side API key. Redirects are never followed. */
@Component
public class HttpEvolutionApi implements EvolutionApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final String key;

    public HttpEvolutionApi(@Value("${app.evolution.base-url:}") String base, @Value("${app.evolution.api-key:}") String key) {
        this.base = base == null ? "" : base.trim().replaceAll("/+$", "");
        this.key = key == null ? "" : key.trim();
    }

    @Override
    public Map<String, Object> call(String method, String path, Map<String, Object> body) {
        if (base.isEmpty()) throw new EvolutionException("Evolution API is not configured", 0);
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).header("apikey", key).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String text = res.body() == null ? "" : res.body();
            if (res.statusCode() / 100 != 2) {
                String msg = text.length() > 300 ? text.substring(0, 300) : text;
                throw new EvolutionException(msg.isBlank() ? "HTTP " + res.statusCode() : msg, res.statusCode());
            }
            if (text.isBlank()) return Map.of();
            Object parsed = JSON.readValue(text, Object.class);
            return parsed instanceof Map<?, ?> ? JSON.convertValue(parsed, new TypeReference<Map<String, Object>>() {}) : Map.of("data", parsed);
        } catch (EvolutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvolutionException("Evolution request was interrupted", 0);
        } catch (Exception e) {
            throw new EvolutionException("Evolution API could not be reached", 0);
        }
    }
}
