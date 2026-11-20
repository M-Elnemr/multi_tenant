package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.integrations.HttpGateway;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * X (Twitter) direct messages: the owner logs in to X (OAuth 2.0 with PKCE), the assistant answers the DMs that arrive for that account, and replies go out through the DM API.
 * One webhook registered with X serves every shop (the account a DM is for is in the event). X bills the app per event, so every reply is a paid call: the shared safeguards
 * (burst joining, per-customer throttle, wallet charging, shop switch) apply as on the other networks.
 * Built from X's public documentation; it works only once the platform has an X developer app (client id and secret, an app bearer token) configured.
 */
@Service
public class XService implements SocialChannel {
    private static final Logger log = LoggerFactory.getLogger(XService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PLATFORM = "X";
    static final String SCOPES = "dm.read dm.write tweet.read users.read offline.access";

    private final JdbcClient jdbc;
    private final HttpGateway http;
    private final TokenCrypto crypto;
    private final SocialBridge bridge;
    private final String clientId, clientSecret, bearer, rootDomain;

    public XService(JdbcClient jdbc, HttpGateway http, TokenCrypto crypto, @org.springframework.context.annotation.Lazy SocialBridge bridge, @Value("${app.x.client-id:}") String clientId,
                    @Value("${app.x.client-secret:}") String clientSecret, @Value("${app.x.bearer-token:}") String bearer, @Value("${app.platform.root-domain}") String rootDomain) {
        this.jdbc = jdbc;
        this.http = http;
        this.crypto = crypto;
        this.bridge = bridge;
        this.clientId = blank(clientId);
        this.clientSecret = blank(clientSecret);
        this.bearer = blank(bearer);
        this.rootDomain = rootDomain;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Override public String platform() { return PLATFORM; }

    @Override public int maxLength() { return 3000; }

    public boolean available() { return clientId != null && clientSecret != null && bearer != null; }

    public String redirectUri() { return "https://" + rootDomain + "/api/v1/chat/x/callback"; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String b64url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private byte[] hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- login (OAuth 2.0 + PKCE; the verifier is derived from the signed state, so nothing is stored while the owner is on X) ----

    private String sign(String payload) {
        String p = b64url(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64url(hmac(clientSecret, "state|" + p));
    }

    private String[] verify(String state) {
        if (state == null || clientSecret == null || !state.contains(".")) return null;
        String p = state.substring(0, state.indexOf('.'));
        if (!MessageDigest.isEqual(sign(new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) return null;
        String[] parts = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8).split("\\.");
        if (parts.length != 3 || Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) return null;
        return parts;
    }

    private String verifierOf(String state) { return b64url(hmac(clientSecret, "pkce|" + state)); }

    public String startUrl(UUID tenantId, UUID userId) {
        if (!available()) throw BusinessException.badRequest("X_NOT_CONFIGURED", "Connecting X is not set up on the server yet");
        String state = sign(tenantId + "." + userId + "." + (Instant.now().getEpochSecond() + 900));
        String challenge;
        try { challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifierOf(state).getBytes(StandardCharsets.US_ASCII))); } catch (Exception e) { throw new IllegalStateException(e); }
        return "https://x.com/i/oauth2/authorize?response_type=code&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri()) + "&scope=" + enc(SCOPES) + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
    }

    private Map<String, String> basic() { return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8))); }

    private JsonNode token(Map<String, String> fields) {
        HttpGateway.Res r = http.send(HttpGateway.Req.form("https://api.x.com/2/oauth2/token", basic(), fields, "x-token"));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
        return r.json();
    }

    /** Returns where to send the browser next (the shop's connect screen, with a result flag). */
    public String callback(String code, String state) {
        String[] st = verify(state);
        if (st == null) throw BusinessException.badRequest("INVALID_STATE", "This connection link has expired, please start again");
        UUID tenantId = UUID.fromString(st[0]), userId = UUID.fromString(st[1]);
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Shop not found"));
        String back = "https://" + host + "/dashboard/channels/connect";
        if (code == null || code.isBlank()) return back + "?error=denied";
        try {
            JsonNode t = token(Map.of("code", code, "grant_type", "authorization_code", "client_id", clientId, "redirect_uri", redirectUri(), "code_verifier", verifierOf(state)));
            String access = t.path("access_token").asText("");
            if (access.isEmpty()) return back + "?error=failed";
            HttpGateway.Res me = http.send(HttpGateway.Req.get("https://api.x.com/2/users/me", Map.of("Authorization", "Bearer " + access), "x-me"));
            if (!me.ok() || me.json().path("data").path("id").asText("").isEmpty()) return back + "?error=failed";
            String xId = me.json().path("data").path("id").asText();
            String username = me.json().path("data").path("username").asText("");
            bridge.upsertChannel(tenantId, userId, PLATFORM, xId, username.isEmpty() ? "X" : "@" + username, tokens(access, t.path("refresh_token").asText(""), t.path("expires_in").asLong(7200)), xId, true);
            subscribe(access, xId);
            return back + "?connected=X";
        } catch (RuntimeException e) {
            log.warn("X login failed: {}", e.getMessage());
            return back + "?error=failed";
        }
    }

    private String tokens(String access, String refresh, long expiresIn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access", access);
        m.put("refresh", refresh);
        m.put("expiresAt", Instant.now().getEpochSecond() + Math.max(60, expiresIn - 60));
        try { return JSON.writeValueAsString(m); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Asks X to send this account's received DMs to our webhook (best effort: the account stays connected and a later call can repeat it). */
    private void subscribe(String access, String xId) {
        String hook = jdbc.sql("SELECT value FROM chat.social_config WHERE platform = :p AND key = 'webhook_id'").param("p", PLATFORM).query(String.class).optional().orElse(null);
        if (hook == null) { log.info("No X webhook registered yet: DMs of account {} will arrive once the platform registers it", xId); return; }
        try {
            http.call(HttpGateway.Req.json("POST", "https://api.x.com/2/activity/subscriptions", Map.of("Authorization", "Bearer " + access), Map.of("event_type", "dm.received", "filter", Map.of("user_id", xId), "webhook_id", hook, "tag", "elmanassa-dm"), "x-sub"));
        } catch (RuntimeException e) {
            log.warn("Could not subscribe X account {} to DMs: {}", xId, e.getMessage());
        }
    }

    /** One-time, by the platform team: registers our webhook address with X (X then runs its CRC check against it). Returns X's webhook id. */
    public String registerWebhook() {
        if (!available()) throw BusinessException.badRequest("X_NOT_CONFIGURED", "X is not set up on the server yet");
        HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", "https://api.x.com/2/webhooks", Map.of("Authorization", "Bearer " + bearer), Map.of("url", "https://" + rootDomain + "/api/v1/chat/x/webhook"), "x-hook"));
        if (!r.ok()) throw BusinessException.badRequest("X_WEBHOOK_FAILED", HttpGateway.describe(r));
        String id = r.json().path("data").path("id").asText("");
        if (id.isEmpty()) throw BusinessException.badRequest("X_WEBHOOK_FAILED", "X did not return a webhook id");
        jdbc.sql("INSERT INTO chat.social_config (platform, key, value) VALUES (:p, 'webhook_id', :v) ON CONFLICT (platform, key) DO UPDATE SET value = :v").param("p", PLATFORM).param("v", id).update();
        return id;
    }

    // ---- webhook ---------------------------------------------------------------------------------------------

    /** X's check that we own the address: our answer is the HMAC of its token with the app secret. */
    public Map<String, String> crc(String crcToken) {
        if (clientSecret == null || crcToken == null) throw BusinessException.badRequest("INVALID_REQUEST", "Missing crc_token");
        return Map.of("response_token", "sha256=" + Base64.getEncoder().encodeToString(hmac(clientSecret, crcToken)));
    }

    public boolean validSignature(String raw, String header) {
        if (clientSecret == null || header == null) return false;
        String expected = "sha256=" + Base64.getEncoder().encodeToString(hmac(clientSecret, raw));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }

    public void handle(String raw) {
        try {
            JsonNode data = JSON.readTree(raw).path("data");
            if (!"dm.received".equals(data.path("event_type").asText())) return;
            String shop = data.path("filter").path("user_id").asText("");
            JsonNode payload = data.path("payload");
            for (JsonNode ev : payload.path("direct_message_events")) {
                if (!"message_create".equals(ev.path("type").asText())) continue;
                JsonNode mc = ev.path("message_create");
                String sender = mc.path("sender_id").asText("");
                if (sender.isEmpty() || sender.equals(shop)) continue;   // our own message is not a customer's
                String name = payload.path("users").path(sender).path("data").path("name").asText(payload.path("users").path(sender).path("data").path("username").asText(""));
                bridge.inbound(PLATFORM, shop, sender, ev.path("id").asText(), mc.path("message_data").path("text").asText(""), name.isEmpty() ? null : name);
            }
        } catch (Exception e) {
            log.warn("X event not handled: {}", e.toString());
        }
    }

    // ---- sending ---------------------------------------------------------------------------------------------

    /** The access token of a connected account, refreshed first when it is about to expire. */
    private String accessToken(Map<String, Object> channel) {
        try {
            JsonNode t = JSON.readTree(bridge.secret(channel));
            if (t.path("expiresAt").asLong(0) > Instant.now().getEpochSecond() || t.path("refresh").asText("").isEmpty()) return t.path("access").asText();
            JsonNode n = token(Map.of("grant_type", "refresh_token", "refresh_token", t.path("refresh").asText(), "client_id", clientId));
            String access = n.path("access_token").asText("");
            if (access.isEmpty()) throw new HttpGateway.ApiException(401, "X did not renew the login");
            jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = :p AND external_id = :e").param("k", crypto.encrypt(tokens(access, n.path("refresh_token").asText(t.path("refresh").asText()), n.path("expires_in").asLong(7200))))
                    .param("p", PLATFORM).param("e", channel.get("external_id")).update();
            return access;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new HttpGateway.ApiException(401, "The X login is unreadable: connect again");
        }
    }

    @Override
    public String send(Map<String, Object> channel, String to, String text, String imageUrl) {
        String body = imageUrl != null && !imageUrl.isBlank() ? text + "\n" + imageUrl : text;   // a link to the picture: DM media needs an upload step
        HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", "https://api.x.com/2/dm_conversations/with/" + enc(to) + "/messages", Map.of("Authorization", "Bearer " + accessToken(channel)), Map.of("text", body), "x-dm"));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status() == 0 ? 502 : r.status(), HttpGateway.describe(r));
        String id = r.json().path("data").path("dm_event_id").asText("");
        return id.isEmpty() ? null : id;
    }

    // ---- dashboard -------------------------------------------------------------------------------------------

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("webhookReady", jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.social_config WHERE platform = :p AND key = 'webhook_id')").param("p", PLATFORM).query(Boolean.class).single());
        m.put("channel", Rows.camel(jdbc.sql("SELECT id, name, status, created_at FROM chat.channels WHERE tenant_id = :t AND platform = :p ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", PLATFORM).query().listOfRows()).stream().findFirst().orElse(null));
        return m;
    }
}
