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

@Component
public class HttpMetaGraph implements MetaGraph {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public HttpMetaGraph(@Value("${app.meta.graph-url:https://graph.facebook.com}") String url, @Value("${app.meta.graph-version:v21.0}") String version) {
        this.base = url + "/" + version + "/";
    }

    @Override public Map<String, Object> get(String path, String token) { return call(HttpRequest.newBuilder(URI.create(base + encode(path))).GET(), token); }

    @Override
    public Map<String, Object> post(String path, String token, Map<String, Object> body) {
        try {
            return call(HttpRequest.newBuilder(URI.create(base + encode(path))).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))), token);
        } catch (MetaException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaException("Meta request failed");
        }
    }

    /** Field expansions such as {@code instagram_business_account{id,username}} contain characters a URI does not allow. */
    private static String encode(String path) { return path.replace("{", "%7B").replace("}", "%7D").replace(" ", "%20"); }

    private Map<String, Object> call(HttpRequest.Builder b, String token) {
        try {
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> res = http.send(b.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> out = res.body() == null || res.body().isBlank() ? Map.of() : JSON.readValue(res.body(), new TypeReference<Map<String, Object>>() {});
            if (res.statusCode() / 100 != 2) {
                Object err = out.get("error");
                String msg = err instanceof Map<?, ?> m && m.get("message") != null ? String.valueOf(m.get("message")) : "HTTP " + res.statusCode();
                throw new MetaException(msg);
            }
            return out;
        } catch (MetaException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetaException("Meta request was interrupted");
        } catch (Exception e) {
            throw new MetaException("Meta could not be reached");
        }
    }
}
