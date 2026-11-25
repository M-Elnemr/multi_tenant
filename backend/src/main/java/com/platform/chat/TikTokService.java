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
 * TikTok Business Messaging API, used directly (no intermediary), exactly as TikTok's own documentation describes it (business-api.tiktok.com, v1.3; notes in .mission/tiktok-official-notes.md):
 * the owner authorizes our developer app (auth code, then a 1-day access token and a 1-year refresh token), TikTok sends every message to ONE webhook of the app (signed with TikTok-Signature),
 * and replies go out with /business/message/send/ to the conversation id from the webhook. TikTok's rules are enforced here as well as by TikTok: only a conversation the customer started, 10 messages in the
 * first 48 hours, unlimited after the customer replies, 3 more after 48 hours of silence. Authorized does not mean able to message: after the login the account is tested, and the shop is told which
 * of approval, region or login is missing. Needs the platform's approved developer app and its data security and privacy review: until then {@link #available()} is false.
 */
@Service
public class TikTokService implements SocialChannel {
    private static final Logger log = LoggerFactory.getLogger(TikTokService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PLATFORM = "TIKTOK";
    /** Replay protection: TikTok's own sample accepts 5 seconds; a minute of clock difference and delivery delay is allowed, and a repeated message id is never answered twice. */
    static final long MAX_AGE_SECONDS = 60;

    private final JdbcClient jdbc;
    private final HttpGateway http;
    private final TokenCrypto crypto;
    private final SocialBridge bridge;
    private final ChatTimeline timeline;
    private final String appId, appSecret, authUrl, rootDomain, api;

    public TikTokService(JdbcClient jdbc, HttpGateway http, TokenCrypto crypto, @org.springframework.context.annotation.Lazy SocialBridge bridge, ChatTimeline timeline,
                         @Value("${app.tiktok.app-id:}") String appId, @Value("${app.tiktok.app-secret:}") String appSecret, @Value("${app.tiktok.auth-url:}") String authUrl,
                         @Value("${app.platform.root-domain}") String rootDomain, @Value("${app.tiktok.api-base:https://business-api.tiktok.com/open_api/v1.3}") String api) {
        this.jdbc = jdbc;
        this.http = http;
        this.crypto = crypto;
        this.bridge = bridge;
        this.timeline = timeline;
        this.appId = blank(appId);
        this.appSecret = blank(appSecret);
        this.authUrl = blank(authUrl);
        this.rootDomain = rootDomain;
        this.api = api;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Override public String platform() { return PLATFORM; }

    @Override public int maxLength() { return 5500; }   // TikTok: 6,000 characters including spaces and emojis

    /** TikTok lets a business answer for 48 hours after the customer's message (a little less here, so a reply never lands on the edge). */
    @Override public int windowHours() { return 47; }

    /** The platform has an approved developer app with its authorization URL (copied from TikTok's developer portal) and secret. */
    public boolean available() { return appId != null && appSecret != null && authUrl != null; }

    public String redirectUri() { return "https://" + rootDomain + "/api/v1/chat/tiktok/callback"; }

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

    // ---- authorization ----------------------------------------------------------------------------------------

    private String sign(String payload) {
        String p = b64url(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + b64url(hmac(appSecret, "tt-state|" + p));
    }

    private String[] verify(String state) {
        if (state == null || appSecret == null || !state.contains(".")) return null;
        try {
            String p = state.substring(0, state.indexOf('.'));
            String payload = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) return null;
            String[] parts = payload.split("\\.");
            return parts.length == 3 && Long.parseLong(parts[2]) >= Instant.now().getEpochSecond() ? parts : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The authorization URL of our developer app with a signed state (TikTok echoes it back), and disable_auto_auth so an owner who authorized before still gets the permission page and an auth code. */
    public String startUrl(UUID tenantId, UUID userId) {
        if (!available()) throw BusinessException.badRequest("TIKTOK_NOT_CONFIGURED", "Connecting TikTok is not set up on the server yet");
        String state = sign(tenantId + "." + userId + "." + (Instant.now().getEpochSecond() + 900));
        return authUrl + (authUrl.contains("?") ? "&" : "?") + "state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) + "&disable_auto_auth=1";
    }

    private JsonNode post(String path, Map<String, String> headers, Object body) {
        HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", api + path, headers, body, "tiktok"));
        return r.json().isMissingNode() || r.body() == null || r.body().isBlank() ? JSON.createObjectNode().put("code", r.status()).put("message", "HTTP " + r.status()) : r.json();
    }

    private JsonNode get(String path, Map<String, String> headers) {
        HttpGateway.Res r = http.send(HttpGateway.Req.get(api + path, headers, "tiktok"));
        return r.body() == null || r.body().isBlank() ? JSON.createObjectNode().put("code", r.status()).put("message", "HTTP " + r.status()) : r.json();
    }

    private String tokens(JsonNode d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("access", d.path("access_token").asText());
        m.put("refresh", d.path("refresh_token").asText());
        m.put("accessExp", Instant.now().getEpochSecond() + Math.max(60, d.path("expires_in").asLong(86400) - 120));
        m.put("refreshExp", Instant.now().getEpochSecond() + d.path("refresh_token_expires_in").asLong(31536000));
        try { return JSON.writeValueAsString(m); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Where the browser goes next: the shop's connect screen, with the result. */
    public String callback(String code, String state) {
        String[] st = verify(state);
        if (st == null) throw BusinessException.badRequest("INVALID_STATE", "This connection link has expired, please start again");
        UUID tenantId = UUID.fromString(st[0]), userId = UUID.fromString(st[1]);
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Shop not found"));
        String back = "https://" + host + "/dashboard/channels/connect";
        if (code == null || code.isBlank()) return back + "?error=denied";   // refused, or TikTok skipped the page for an owner who authorized before (we ask for it again with disable_auto_auth)
        try {
            JsonNode res = post("/tt_user/oauth2/token/", Map.of(), Map.of("client_id", appId, "client_secret", appSecret, "grant_type", "authorization_code", "auth_code", code, "redirect_uri", redirectUri()));
            if (res.path("code").asInt(-1) != 0) { log.warn("TikTok token exchange refused: {} {}", res.path("code").asText(), res.path("message").asText()); return back + "?error=failed"; }
            JsonNode d = res.path("data");
            String openId = d.path("open_id").asText("");
            if (d.path("access_token").asText("").isEmpty() || openId.isEmpty()) return back + "?error=failed";
            String scope = d.path("scope").asText("");
            boolean scopes = scope.contains("message.list.send") && (scope.contains("message.list.read") || scope.contains("message.list.manage"));
            String state2 = "CONNECTED", note = null;
            if (!scopes) { state2 = "APPROVAL_REQUIRED"; note = "TikTok did not grant the messaging permissions: the developer app needs the Business Messaging permission and TikTok's approval."; }
            else {
                var probe = probe(d.path("access_token").asText(), openId);
                state2 = probe[0];
                note = probe[1];
            }
            String name = "TikTok";
            bridge.upsertChannel(tenantId, userId, PLATFORM, openId, name, tokens(d), openId, "CONNECTED".equals(state2));
            jdbc.sql("UPDATE chat.channels SET connection_state = :s, status_note = :n WHERE platform = :p AND external_id = :e").param("s", state2).param("n", note).param("p", PLATFORM).param("e", openId).update();
            return back + ("CONNECTED".equals(state2) ? "?connected=TIKTOK" : "?error=" + ("RESTRICTED".equals(state2) ? "restricted" : "approval"));
        } catch (RuntimeException e) {
            log.warn("TikTok login failed: {}", e.getMessage());
            return back + "?error=failed";
        }
    }

    /**
     * Authorized is not able to message: one documented call (the conversation list) tells which. Returns {state, note}.
     * 40001 = no permission (the app is not approved for messaging, or the account is not Advanced Access / a Verified Business Account), 40105 = the login is not accepted.
     */
    private String[] probe(String access, String openId) {
        JsonNode r = get("/business/message/conversation/list/?business_id=" + URLEncoder.encode(openId, StandardCharsets.UTF_8) + "&conversation_type=SINGLE&limit=1", Map.of("Access-Token", access));
        int code = r.path("code").asInt(-1);
        String msg = r.path("message").asText("");
        if (code == 0) return new String[] {"CONNECTED", null};
        return switch (code) {
            case 40001 -> new String[] {"APPROVAL_REQUIRED", "TikTok refused messaging for this account: " + msg + ". The account must be a Verified Business Account (or have Advanced Access) and our app must be approved for Business Messaging."};
            case 40105 -> new String[] {"REAUTH_REQUIRED", "TikTok did not accept the login: connect again."};
            case 40100, 51065 -> new String[] {"UNAVAILABLE", "TikTok is busy right now: try again in a few minutes."};
            default -> new String[] {"RESTRICTED", "TikTok does not allow messaging for this account (" + code + (msg.isEmpty() ? "" : ": " + msg) + "). Accounts signed up in the EEA, Switzerland and the UK are not supported."};
        };
    }

    /** The access token of a connected account, renewed first when it is about to expire (the refresh token lasts a year; after that the owner must connect again). */
    private String accessToken(Map<String, Object> channel) {
        try {
            JsonNode t = JSON.readTree(bridge.secret(channel));
            if (t.path("accessExp").asLong(0) > Instant.now().getEpochSecond()) return t.path("access").asText();
            if (t.path("refreshExp").asLong(0) <= Instant.now().getEpochSecond()) { needLogin(channel); throw new HttpGateway.ApiException(401, "The TikTok login expired: connect TikTok again"); }
            JsonNode r = post("/tt_user/oauth2/refresh_token/", Map.of(), Map.of("client_id", appId, "client_secret", appSecret, "grant_type", "refresh_token", "refresh_token", t.path("refresh").asText()));
            if (r.path("code").asInt(-1) != 0) { needLogin(channel); throw new HttpGateway.ApiException(401, "TikTok did not renew the login: connect TikTok again"); }
            JsonNode d = r.path("data");
            jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = :p AND external_id = :e").param("k", crypto.encrypt(tokens(d))).param("p", PLATFORM).param("e", channel.get("external_id")).update();
            return d.path("access_token").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            needLogin(channel);
            throw new HttpGateway.ApiException(401, "The TikTok login is unreadable: connect TikTok again");
        }
    }

    private void needLogin(Map<String, Object> channel) {
        jdbc.sql("UPDATE chat.channels SET status = 'PAUSED', connection_state = 'REAUTH_REQUIRED', status_note = 'The TikTok login expired or was revoked: connect TikTok again.' WHERE platform = :p AND external_id = :e").param("p", PLATFORM).param("e", channel.get("external_id")).update();
    }

    // ---- the webhook ------------------------------------------------------------------------------------------

    /** TikTok-Signature is {@code t=<seconds>,s=<hex>}: s = HMAC-SHA256 with the app secret of "t.body"; an old timestamp is a replay. */
    public boolean validSignature(String raw, String header) {
        if (appSecret == null || header == null || raw == null) return false;
        String t = null, s = null;
        for (String part : header.split(",")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            if (part.substring(0, i).trim().equals("t")) t = part.substring(i + 1).trim();
            if (part.substring(0, i).trim().equals("s")) s = part.substring(i + 1).trim();
        }
        if (t == null || s == null) return false;
        long age;
        try { age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(t)); } catch (NumberFormatException e) { return false; }
        if (age > MAX_AGE_SECONDS) return false;
        StringBuilder hex = new StringBuilder();
        for (byte b : hmac(appSecret, t + "." + raw)) hex.append(String.format("%02x", b));
        return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), s.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public void handle(String raw) {
        try {
            JsonNode e = JSON.readTree(raw);
            if (!"im_receive_msg".equals(e.path("event").asText())) return;   // our own sent messages (im_send_msg), reads, automatic-message updates and the EEA event (no content) need no answer
            String business = e.path("user_openid").asText("");
            JsonNode c = JSON.readTree(e.path("content").asText("{}"));
            if (!"personal_account".equals(c.path("from_user").path("role").asText("personal_account"))) return;   // a message from the business itself
            String conversation = c.path("conversation_id").asText("");
            String text = "text".equals(c.path("type").asText()) ? c.path("text").path("body").asText("") : "";
            String name = c.path("from").asText("");
            bridge.inbound(PLATFORM, business, conversation, c.path("message_id").asText(""), text, name.isEmpty() ? null : name);
        } catch (Exception ex) {
            log.warn("TikTok event not handled: {}", ex.toString());   // a 200 anyway: TikTok would only resend the same event
        }
    }

    /** One-time, by the platform team: subscribes the app to direct message events at our address (app level: every business that authorized is covered). */
    public Map<String, Object> registerWebhook() {
        if (!available()) throw BusinessException.badRequest("TIKTOK_NOT_CONFIGURED", "TikTok is not set up on the server yet");
        JsonNode r = post("/business/webhook/update/", Map.of(), Map.of("app_id", appId, "secret", appSecret, "event_type", "DIRECT_MESSAGE", "callback_url", "https://" + rootDomain + "/api/v1/chat/tiktok/webhook"));
        if (r.path("code").asInt(-1) != 0) throw BusinessException.badRequest("TIKTOK_WEBHOOK_FAILED", r.path("message").asText("TikTok refused the webhook"));
        jdbc.sql("INSERT INTO chat.social_config (platform, key, value) VALUES (:p, 'webhook', 'registered') ON CONFLICT (platform, key) DO UPDATE SET value = 'registered'").param("p", PLATFORM).update();
        return Map.of("registered", true, "eventType", "DIRECT_MESSAGE");
    }

    // ---- sending, inside TikTok's limits ----------------------------------------------------------------------

    /**
     * May one more message go to this conversation? TikTok: up to 10 within 48 h of the customer's message; unlimited for 48 h after the customer has replied to us; after 48 h of silence only 3 more
     * until they write again. Counted from what we really sent (chat.social_sends) and what the customer wrote.
     */
    boolean mayReply(String account, String conversation, UUID tenantId) {
        Instant lastUser = jdbc.sql("SELECT max(m.created_at) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND c.channel = 'TIKTOK' AND c.external_id = :e AND m.role = 'user'")
                .param("t", tenantId).param("e", conversation).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant).orElse(null);
        if (lastUser == null) return false;   // a conversation the customer never started: never
        boolean replied = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id
                  WHERE c.tenant_id = :t AND c.channel = 'TIKTOK' AND c.external_id = :e AND m.role = 'user'
                    AND EXISTS (SELECT 1 FROM chat.social_sends s WHERE s.platform = 'TIKTOK' AND s.account_id = :a AND s.conversation_ref = :e AND s.sent_at < m.created_at))
                """).param("t", tenantId).param("e", conversation).param("a", account).query(Boolean.class).single();
        Instant now = Instant.now();
        boolean inWindow = now.isBefore(lastUser.plusSeconds(48L * 3600));
        if (inWindow && replied) return true;   // an active conversation: unlimited
        if (inWindow) return countSince(account, conversation, lastUser) < 10;
        return countSince(account, conversation, lastUser.plusSeconds(48L * 3600)) < 3;
    }

    private long countSince(String account, String conversation, Instant since) {
        return jdbc.sql("SELECT count(*) FROM chat.social_sends WHERE platform = 'TIKTOK' AND account_id = :a AND conversation_ref = :e AND sent_at > :s").param("a", account).param("e", conversation).param("s", java.sql.Timestamp.from(since)).query(Long.class).single();
    }

    @Override
    public String send(Map<String, Object> channel, String to, String text, String imageUrl) {
        String account = String.valueOf(channel.get("external_id"));
        UUID tenantId = (UUID) channel.get("tenant_id");
        if (!mayReply(account, to, tenantId)) throw new HttpGateway.ApiException(429, "TikTok does not allow another message to this customer right now (messaging limits): the shop can reply from the TikTok app");
        String body = imageUrl != null && !imageUrl.isBlank() ? text + "\n" + imageUrl : text;   // a link: sending a picture needs an upload and a market that supports it
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("business_id", account);
        req.put("recipient_type", "CONVERSATION");
        req.put("recipient", to);
        req.put("message_type", "TEXT");
        req.put("text", Map.of("body", body.length() > 5900 ? body.substring(0, 5900) : body));
        for (int attempt = 0; ; attempt++) {
            JsonNode r = post("/business/message/send/", Map.of("Access-Token", accessToken(channel)), req);
            int code = r.path("code").asInt(-1);
            if (code == 0) {
                jdbc.sql("INSERT INTO chat.social_sends (platform, account_id, conversation_ref) VALUES ('TIKTOK', :a, :c)").param("a", account).param("c", to).update();
                String id = r.path("data").path("message").path("message_id").asText("");
                return id.isEmpty() ? null : id;
            }
            if ((code == 40100 || code == 51065) && attempt < 2) { sleep(600L * (attempt + 1)); continue; }   // TikTok says to retry a system error and to slow down when throttled
            if (code == 40105 && attempt < 1) { expire(channel); continue; }   // the token was refused: renew it once and try again
            if (code == 40105) needLogin(channel);
            throw new HttpGateway.ApiException(code == 40064 ? 403 : code == 40100 ? 429 : 502, "TikTok refused the message (" + code + (r.path("message").asText("").isEmpty() ? "" : ": " + r.path("message").asText()) + ")");
        }
    }

    private void expire(Map<String, Object> channel) {
        try {
            JsonNode t = JSON.readTree(bridge.secret(channel));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("access", t.path("access").asText()); m.put("refresh", t.path("refresh").asText()); m.put("accessExp", 0L); m.put("refreshExp", t.path("refreshExp").asLong());
            jdbc.sql("UPDATE chat.channels SET token_enc = :k WHERE platform = :p AND external_id = :e").param("k", crypto.encrypt(JSON.writeValueAsString(m))).param("p", PLATFORM).param("e", channel.get("external_id")).update();
            channel.put("token_enc", jdbc.sql("SELECT token_enc FROM chat.channels WHERE platform = :p AND external_id = :e").param("p", PLATFORM).param("e", channel.get("external_id")).query(String.class).single());
        } catch (Exception e) {
            throw new HttpGateway.ApiException(401, "The TikTok login is unreadable: connect TikTok again");
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    // ---- dashboard --------------------------------------------------------------------------------------------

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("webhookReady", jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.social_config WHERE platform = :p AND key = 'webhook')").param("p", PLATFORM).query(Boolean.class).single());
        m.put("channel", Rows.camel(jdbc.sql("SELECT id, name, status, connection_state, status_note, created_at FROM chat.channels WHERE tenant_id = :t AND platform = :p ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", PLATFORM).query().listOfRows()).stream().findFirst().orElse(null));
        return m;
    }

    /** Re-tests a connected account (after the shop upgraded the TikTok account or TikTok approved the app). */
    public Map<String, Object> recheck(UUID tenantId) {
        var ch = jdbc.sql("SELECT tenant_id, external_id, token_enc, owner_ref, status FROM chat.channels WHERE tenant_id = :t AND platform = :p ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", PLATFORM).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "TikTok is not connected"));
        Map<String, Object> row = new java.util.HashMap<>(ch);
        String[] p;
        try { p = probe(accessToken(row), String.valueOf(ch.get("external_id"))); } catch (HttpGateway.ApiException e) { p = new String[] {"REAUTH_REQUIRED", e.getMessage()}; }
        jdbc.sql("UPDATE chat.channels SET connection_state = :s, status_note = :n, status = CASE WHEN :s = 'CONNECTED' THEN 'ACTIVE' ELSE 'PAUSED' END WHERE tenant_id = :t AND platform = :p AND external_id = :e").param("s", p[0]).param("n", p[1]).param("t", tenantId).param("p", PLATFORM).param("e", ch.get("external_id")).update();
        return status(tenantId);
    }
}
