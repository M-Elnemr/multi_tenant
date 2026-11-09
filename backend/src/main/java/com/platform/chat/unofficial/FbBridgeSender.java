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
 * Messenger through the {@code infra/fb-bridge} sidecar (FastAPI around fbchat-v2). The session is the owner's pasted facebook.com cookie string with {@code i_user=<page id>} added by the
 * sidecar, so the requests act as the Page. Same typed outcomes and the same rules as {@link InstagramBridgeSender}; cookies are never logged and never returned.
 *
 * Acting as a Page through fbchat-v2 is undocumented and unproven; this class is written against the sidecar contract and tested only with a fake HTTP server.
 */
@Component
@Order(50)
public class FbBridgeSender implements OutOfWindowSender {
    private static final Logger log = LoggerFactory.getLogger(FbBridgeSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final String base, secret;

    public FbBridgeSender(@Value("${app.meta.unofficial.fb-bridge-url:}") String base, @Value("${app.meta.unofficial.fb-bridge-secret:}") String secret) {
        this.base = base == null ? "" : base.trim().replaceAll("/+$", "");
        this.secret = secret == null ? "" : secret.trim();
    }

    @Override public boolean handles(String platform) { return "FACEBOOK".equals(platform); }

    private boolean configured() { return !base.isEmpty() && !secret.isEmpty(); }

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
            log.warn("Messenger bridge call {} {} failed: {}", method, path, e.getClass().getSimpleName());   // class only: never a body (it carries the cookies on login)
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
        body.put("recipient", recipientRef);
        body.put("text", text);
        body.put("typing_ms", Math.max(0, typingMs));
        int[] st = {0};
        JsonNode n = call("POST", "/messages/send", body, Duration.ofSeconds(60 + typingMs / 1000), st);
        if (n == null) return Result.of(Kind.FAILED, "BRIDGE_UNREACHABLE");
        if (st[0] == 401 || st[0] == 403) return Result.of(Kind.FAILED, "BRIDGE_AUTH");
        String detail = n.path("detail").asText(null);
        return switch (n.path("result").asText("")) {
            case "SENT" -> Result.sent();
            case "BLOCKED" -> Result.of(Kind.BLOCKED, detail);
            case "SESSION_EXPIRED" -> Result.of(Kind.SESSION_EXPIRED, detail);
            case "NOT_FOUND" -> Result.of(Kind.NOT_FOUND, detail);
            default -> Result.of(Kind.FAILED, detail == null ? "HTTP " + st[0] : detail);
        };
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
    public LoginResult login(UUID tenant, String platform, String username, String password, String totp) { return LoginResult.error("UNSUPPORTED"); }

    @Override
    public LoginResult loginWithCookies(UUID tenant, String platform, String cookies, String pageId) {
        if (!handles(platform)) return LoginResult.error("UNSUPPORTED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant.toString());
        body.put("cookies", cookies);
        body.put("page_id", pageId);
        int[] st = {0};
        JsonNode n = call("POST", "/sessions/login", body, Duration.ofSeconds(90), st);
        if (n == null) return LoginResult.error("UNREACHABLE");
        if (n.path("ok").asBoolean(false)) return LoginResult.ok(n.path("account").asText(pageId));
        String err = n.path("error").asText("FAILED");
        if ("PAGE_IDENTITY_NOT_APPLIED".equals(n.path("detail").asText(""))) return LoginResult.error("PAGE_IDENTITY");
        if ("CHECKPOINT".equals(err)) err = "CHALLENGE";
        return LoginResult.error(List.of("CHALLENGE", "BAD_CREDENTIALS").contains(err) ? err : "FAILED");
    }

    @Override
    public ThreadPage listThreads(UUID tenant, String platform, int limit, String before) {
        if (!handles(platform)) return ThreadPage.error("UNSUPPORTED");
        String q = "?limit=" + Math.max(1, Math.min(500, limit)) + (before == null || before.isBlank() ? "" : "&before=" + java.net.URLEncoder.encode(before, java.nio.charset.StandardCharsets.UTF_8));
        int[] st = {0};
        JsonNode n = call("GET", "/facebook/" + tenant + "/threads" + q, null, Duration.ofSeconds(240), st);
        if (n == null || st[0] == 401 || st[0] == 403) return ThreadPage.error("BRIDGE_UNREACHABLE");
        if (!n.path("ok").asBoolean(false)) {
            return ThreadPage.error(switch (n.path("error").asText("FAILED")) {
                case "SESSION_EXPIRED" -> "NEEDS_LOGIN";
                case "RATE_LIMITED" -> "RATE_LIMITED";
                default -> "FAILED";
            });
        }
        List<ContactThread> out = new java.util.ArrayList<>();
        for (JsonNode t : n.path("threads")) {
            String id = t.path("id").asText("");
            if (id.isBlank()) continue;
            out.add(new ContactThread(id, t.path("name").isNull() ? null : t.path("name").asText(null), t.path("last_activity_ms").asLong(0), t.path("unread").asInt(0)));
        }
        String next = n.path("next_before").isNull() ? null : n.path("next_before").asText(null);
        return ThreadPage.of(out, next == null || next.isBlank() ? null : next);
    }

    @Override
    public void logout(UUID tenant, String platform) {
        if (handles(platform)) call("POST", "/sessions/logout", Map.of("tenant", tenant.toString()), Duration.ofSeconds(10), null);
    }
}
