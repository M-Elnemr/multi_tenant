package com.platform.chat.unofficial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Instagram through the {@code infra/ig-bridge} sidecar (a small FastAPI service around the {@code instagrapi} private-API library). The backend only speaks HTTP to it with a
 * shared-secret header; the sidecar keeps one device fingerprint and one session file per shop. The request bodies (which carry the password on login) are never logged here.
 *
 * Written against the sidecar's documented contract and tested only with a fake transport: it has never talked to a real Instagram account.
 */
@Component
@Order(50)
public class InstagramBridgeSender implements OutOfWindowSender {
    private static final Logger log = LoggerFactory.getLogger(InstagramBridgeSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base, secret;

    public InstagramBridgeSender(@Value("${app.meta.unofficial.ig-bridge-url:}") String base, @Value("${app.meta.unofficial.ig-bridge-secret:}") String secret) {
        this.base = base == null ? "" : base.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    @Override public boolean handles(String platform) { return "INSTAGRAM".equals(platform); }

    private boolean configured() { return !base.isEmpty() && !secret.isEmpty(); }

    /** One call; null when the sidecar could not be reached or answered with something unreadable. {@code status[0]} gets the HTTP status. */
    private JsonNode call(String method, String path, Map<String, Object> body, Duration timeout, int[] status) {
        if (!configured()) return null;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("X-Bridge-Secret", secret).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (status != null) status[0] = res.statusCode();
            String text = res.body() == null ? "" : res.body();
            return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Instagram bridge call {} {} failed: {}", method, path, e.getClass().getSimpleName());   // class only: the message could echo the URL, never a body
            return null;
        }
    }

    @Override
    public boolean healthy() {
        int[] st = {0};
        JsonNode n = call("GET", "/health", null, Duration.ofSeconds(4), st);
        return n != null && st[0] / 100 == 2;
    }

    @Override
    public Result send(UUID tenant, String platform, String recipientRef, String text, int typingMs) {
        if (!handles(platform)) return Result.of(Kind.FAILED, "UNSUPPORTED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant.toString());
        body.put("username_or_thread", recipientRef);
        body.put("text", text);
        body.put("typing_ms", Math.max(0, typingMs));
        int[] st = {0};
        JsonNode n = call("POST", "/messages/send", body, Duration.ofSeconds(60 + typingMs / 1000), st);
        if (n == null) return Result.of(Kind.FAILED, "BRIDGE_UNREACHABLE");
        String r = n.path("result").asText("");
        String detail = n.path("detail").asText(null);
        if (st[0] == 401 || st[0] == 403) return Result.of(Kind.FAILED, "BRIDGE_AUTH");
        try {
            return switch (r) {
                case "SENT" -> Result.sent();
                case "BLOCKED" -> Result.of(Kind.BLOCKED, detail);
                case "SESSION_EXPIRED" -> Result.of(Kind.SESSION_EXPIRED, detail);
                case "NOT_FOUND" -> Result.of(Kind.NOT_FOUND, detail);
                default -> Result.of(Kind.FAILED, detail == null ? "HTTP " + st[0] : detail);
            };
        } catch (RuntimeException e) {
            return Result.of(Kind.FAILED, "BAD_RESPONSE");
        }
    }

    @Override
    public SessionStatus status(UUID tenant, String platform) {
        if (!handles(platform)) return new SessionStatus("UNSUPPORTED", null, null);
        int[] st = {0};
        JsonNode n = call("GET", "/sessions/" + tenant + "/status", null, Duration.ofSeconds(8), st);
        if (n == null) return new SessionStatus("UNREACHABLE", null, null);
        if (!n.path("logged_in").asBoolean(false)) return new SessionStatus(n.path("exists").asBoolean(false) ? "NEEDS_LOGIN" : "NONE", null, n.path("detail").asText(null));
        return new SessionStatus("ACTIVE", n.path("account").asText(null), null);
    }

    @Override
    public LoginResult login(UUID tenant, String platform, String username, String password, String totp) {
        if (!handles(platform)) return LoginResult.error("UNSUPPORTED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant.toString());
        body.put("username", username);
        body.put("password", password);
        if (totp != null && !totp.isBlank()) body.put("totp", totp);
        int[] st = {0};
        JsonNode n = call("POST", "/sessions/login", body, Duration.ofSeconds(90), st);
        if (n == null) return LoginResult.error("UNREACHABLE");
        if (n.path("ok").asBoolean(false)) return LoginResult.ok(n.path("account").asText(username));
        String err = n.path("error").asText("FAILED");
        return LoginResult.error(List.of("CHALLENGE", "BAD_CREDENTIALS", "UNREACHABLE", "UNSUPPORTED").contains(err) ? err : "FAILED");
    }

    @Override
    public void logout(UUID tenant, String platform) {
        if (handles(platform)) call("POST", "/sessions/logout", Map.of("tenant", tenant.toString()), Duration.ofSeconds(10), null);
    }
}
