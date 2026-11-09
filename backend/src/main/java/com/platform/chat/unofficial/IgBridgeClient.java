package com.platform.chat.unofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the shop's own Instagram data (followers, recent posts, post comments) through the same {@code infra/ig-bridge} sidecar as {@link InstagramBridgeSender}, for the
 * "new customers" screen. Every call returns a typed page: {@code ok}, or an {@code error} (SESSION_EXPIRED, CHALLENGE, RATE_LIMITED, FAILED, UNREACHABLE). Never throws.
 * Tests replace this bean with a fake; it has never talked to a real account.
 */
@Component
public class IgBridgeClient {
    private static final Logger log = LoggerFactory.getLogger(IgBridgeClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base, secret;

    public record User(String pk, String username, String fullName, boolean isPrivate) {}
    public record UsersPage(boolean ok, String error, List<User> users, String nextCursor) {}
    public record Media(String id, String code, String caption, int commentCount) {}
    public record MediaPage(boolean ok, String error, List<Media> media) {}
    public record Comment(String pk, String text, User user) {}
    public record CommentsPage(boolean ok, String error, List<Comment> comments) {}

    public IgBridgeClient(@Value("${app.meta.unofficial.ig-bridge-url:}") String base, @Value("${app.meta.unofficial.ig-bridge-secret:}") String secret) {
        this.base = base == null ? "" : base.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    private JsonNode get(String path) {
        if (base.isEmpty() || secret.isEmpty()) return null;
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).header("X-Bridge-Secret", secret).GET().build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) return JSON.createObjectNode().put("ok", false).put("error", "FAILED");
            String text = res.body() == null ? "" : res.body();
            return text.isBlank() ? null : JSON.readTree(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Instagram bridge read failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String err(JsonNode n) {
        if (n == null) return "UNREACHABLE";
        String e = n.path("error").asText("FAILED");
        return List.of("SESSION_EXPIRED", "CHALLENGE", "RATE_LIMITED").contains(e) ? e : "FAILED";
    }

    private static User user(JsonNode u) {
        return new User(u.path("pk").asText(""), u.path("username").asText(null), u.path("full_name").asText(null), u.path("is_private").asBoolean(false));
    }

    public UsersPage followers(UUID tenant, int amount, String cursor) {
        JsonNode n = get("/instagram/" + tenant + "/followers?amount=" + amount + (cursor == null || cursor.isBlank() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
        if (n == null || !n.path("ok").asBoolean(false)) return new UsersPage(false, err(n), List.of(), null);
        List<User> out = new ArrayList<>();
        for (JsonNode u : n.path("users")) if (!u.path("pk").asText("").isEmpty()) out.add(user(u));
        String next = n.path("next_cursor").isNull() || n.path("next_cursor").isMissingNode() ? null : n.path("next_cursor").asText(null);
        return new UsersPage(true, null, out, next == null || next.isBlank() ? null : next);
    }

    public MediaPage recentMedia(UUID tenant, int amount) {
        JsonNode n = get("/instagram/" + tenant + "/media/recent?amount=" + amount);
        if (n == null || !n.path("ok").asBoolean(false)) return new MediaPage(false, err(n), List.of());
        List<Media> out = new ArrayList<>();
        for (JsonNode m : n.path("media")) out.add(new Media(m.path("id").asText(""), m.path("code").asText(null), m.path("caption").asText(null), m.path("comment_count").asInt(0)));
        return new MediaPage(true, null, out);
    }

    public CommentsPage comments(UUID tenant, String mediaId, int amount) {
        JsonNode n = get("/instagram/" + tenant + "/media/" + URLEncoder.encode(mediaId, StandardCharsets.UTF_8) + "/comments?amount=" + amount);
        if (n == null || !n.path("ok").asBoolean(false)) return new CommentsPage(false, err(n), List.of());
        List<Comment> out = new ArrayList<>();
        for (JsonNode c : n.path("comments")) if (c.has("user") && !c.path("user").path("pk").asText("").isEmpty()) out.add(new Comment(c.path("pk").asText(null), c.path("text").asText(""), user(c.path("user"))));
        return new CommentsPage(true, null, out);
    }
}
