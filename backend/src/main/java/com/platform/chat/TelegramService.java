package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.integrations.HttpGateway;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Telegram Business: a shop owner connects elmanassa's bot to their own Telegram Business account (Settings > Business > Chatbots), and the assistant then answers the
 * customers who message that account, as the owner, through Telegram's {@code business_connection_id}. One bot serves every shop; which shop a connection belongs to is
 * learned when the owner opens the link from the dashboard (it carries a one-time code the bot receives with /start).
 */
@Service
public class TelegramService implements SocialChannel {
    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String PLATFORM = "TELEGRAM";

    private final JdbcClient jdbc;
    private final HttpGateway http;
    private final SocialBridge bridge;
    private final String token;
    private final String botUsername;
    private final String webhookSecret;
    private final String rootDomain;
    private final boolean registerWebhook;

    public TelegramService(JdbcClient jdbc, HttpGateway http, @org.springframework.context.annotation.Lazy SocialBridge bridge, @Value("${app.telegram.bot-token:}") String token,
                           @Value("${app.telegram.bot-username:}") String botUsername, @Value("${app.telegram.webhook-secret:}") String webhookSecret, @Value("${app.platform.root-domain}") String rootDomain,
                           @Value("${app.telegram.register-webhook:true}") boolean registerWebhook) {
        this.jdbc = jdbc;
        this.http = http;
        this.bridge = bridge;
        this.token = blank(token);
        this.botUsername = blank(botUsername) == null ? null : botUsername.trim().replaceFirst("^@", "");
        this.webhookSecret = blank(webhookSecret);
        this.rootDomain = rootDomain;
        this.registerWebhook = registerWebhook;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    @Override public String platform() { return PLATFORM; }

    @Override public int maxLength() { return 3500; }

    /** Telegram lets a connected bot answer the chats that were active in the last 24 hours. */
    @Override public int windowHours() { return 23; }

    public boolean available() { return token != null && botUsername != null && webhookSecret != null; }

    // ---- Telegram's API --------------------------------------------------------------------------------------

    private JsonNode call(String method, Map<String, Object> body) {
        if (token == null) throw new HttpGateway.ApiException(400, "Telegram is not set up on the server yet");
        HttpGateway.Res r = http.send(HttpGateway.Req.json("POST", "https://api.telegram.org/bot" + token + "/" + method, Map.of(), body, "telegram"));
        JsonNode j = r.json();
        if (!r.ok() || !j.path("ok").asBoolean(false)) throw new HttpGateway.ApiException(r.status() == 0 ? 502 : r.status(), j.path("description").asText("Telegram refused the message"));
        return j.path("result");
    }

    private static Object chatId(String to) { return to.matches("-?\\d{1,18}") ? (Object) Long.parseLong(to) : to; }

    @Override
    public String send(Map<String, Object> channel, String to, String text, String imageUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("business_connection_id", channel.get("external_id"));
        body.put("chat_id", chatId(to));
        JsonNode res;
        if (imageUrl != null && !imageUrl.isBlank()) {
            body.put("photo", imageUrl);
            body.put("caption", text.length() > 1000 ? text.substring(0, 1000) : text);
            res = call("sendPhoto", body);
        } else {
            body.put("text", text);
            res = call("sendMessage", body);
        }
        return res.path("message_id").isMissingNode() ? null : res.path("message_id").asText();
    }

    /** A plain message from the bot to a person who is talking to the bot itself (the owner linking or connecting): never to a customer. */
    private void notify(Object chat, String text) {
        try { call("sendMessage", Map.of("chat_id", chat, "text", text)); } catch (RuntimeException e) { log.info("Could not message the Telegram owner: {}", e.getMessage()); }
    }

    /** Tells Telegram where to send updates (with the secret it will repeat in a header). Safe to call again. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { if (registerWebhook && available()) registerWebhook(); }

    public boolean registerWebhook() {
        try {
            call("setWebhook", Map.of("url", "https://" + rootDomain + "/api/v1/chat/telegram/webhook", "secret_token", webhookSecret,
                    "allowed_updates", List.of("message", "business_connection", "business_message")));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not register the Telegram webhook: {}", e.getMessage());
            return false;
        }
    }

    // ---- shop side: connect ----------------------------------------------------------------------------------

    /** What the dashboard shows: whether the bot exists on this server, whether the owner linked their Telegram, and the connection. */
    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("botUsername", botUsername);
        m.put("linked", jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.social_owners WHERE platform = :p AND tenant_id = :t)").param("p", PLATFORM).param("t", tenantId).query(Boolean.class).single());
        m.put("channel", Rows.camel(jdbc.sql("SELECT id, name, status, created_at FROM chat.channels WHERE tenant_id = :t AND platform = :p ORDER BY created_at LIMIT 1").param("t", tenantId).param("p", PLATFORM).query().listOfRows()).stream().findFirst().orElse(null));
        return m;
    }

    /** A one-time link: opening it in Telegram starts the bot with a code that says which shop is connecting (valid 30 minutes). */
    public Map<String, Object> link(UUID tenantId, UUID userId) {
        if (!available()) throw BusinessException.badRequest("TELEGRAM_NOT_CONFIGURED", "Connecting Telegram is not set up on the server yet");
        String code = randomCode();
        jdbc.sql("DELETE FROM chat.social_links WHERE created_at < now() - interval '30 minutes' OR (tenant_id = :t AND platform = :p)").param("t", tenantId).param("p", PLATFORM).update();
        jdbc.sql("INSERT INTO chat.social_links (code, tenant_id, user_id, platform) VALUES (:c, :t, :u, :p)").param("c", code).param("t", tenantId).param("u", userId).param("p", PLATFORM).update();
        return Map.of("url", "https://t.me/" + botUsername + "?start=" + code, "botUsername", botUsername);
    }

    private static String randomCode() {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 28; i++) b.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return b.toString();
    }

    // ---- webhook ---------------------------------------------------------------------------------------------

    /** True when Telegram repeated our secret in its header (constant-time compare). */
    public boolean validSecret(String header) {
        if (webhookSecret == null || header == null) return false;
        return MessageDigest.isEqual(webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void handle(String raw) {
        try {
            JsonNode u = JSON.readTree(raw);
            if (u.has("business_connection")) onConnection(u.path("business_connection"));
            else if (u.has("business_message")) onBusinessMessage(u.path("business_message"));
            else if (u.has("message")) onBotMessage(u.path("message"));
        } catch (Exception e) {
            log.warn("Telegram update not handled: {}", e.toString());   // Telegram must get a 200 anyway, or it keeps resending the same update
        }
    }

    private static final String HOW = "افتح تيليجرام ← الإعدادات ← تيليجرام للأعمال ← روبوتات المحادثة، وأضف هذا الروبوت واسمح له بالرد على الرسائل. "
            + "/ Open Telegram > Settings > Business > Chatbots, add this bot and allow it to reply to messages.";

    private void onBotMessage(JsonNode m) {
        if (!"private".equals(m.path("chat").path("type").asText())) return;
        String text = m.path("text").asText("").trim();
        Object chat = m.path("chat").path("id").asLong();
        if (!text.startsWith("/start")) return;
        String code = text.length() > 6 ? text.substring(6).trim() : "";
        String tenant = code.isEmpty() ? null : jdbc.sql("DELETE FROM chat.social_links WHERE code = :c AND platform = :p AND created_at > now() - interval '30 minutes' RETURNING tenant_id::text").param("c", code).param("p", PLATFORM).query(String.class).optional().orElse(null);
        if (tenant == null) {
            notify(chat, "افتح الرابط من لوحة التحكم في المنصة لربط متجرك / Open the link from your dashboard to connect your shop.");
            return;
        }
        String owner = m.path("from").path("id").asText();
        jdbc.sql("INSERT INTO chat.social_owners (platform, external_id, tenant_id) VALUES (:p, :e, CAST(:t AS uuid)) ON CONFLICT (platform, external_id) DO UPDATE SET tenant_id = CAST(:t AS uuid), linked_at = now()")
                .param("p", PLATFORM).param("e", owner).param("t", tenant).update();
        String shop = jdbc.sql("SELECT name FROM core.tenants WHERE id = CAST(:t AS uuid)").param("t", tenant).query(String.class).optional().orElse("");
        notify(chat, "✅ تم ربط حسابك بمتجر «" + shop + "» / Linked to «" + shop + "».\n\n" + HOW);
    }

    private void onConnection(JsonNode bc) {
        String id = bc.path("id").asText("");
        String owner = bc.path("user").path("id").asText("");
        Object ownerChat = bc.path("user_chat_id").isMissingNode() ? bc.path("user").path("id").asLong() : bc.path("user_chat_id").asLong();
        if (id.isEmpty() || owner.isEmpty()) return;
        var shop = jdbc.sql("SELECT o.tenant_id::text AS t FROM chat.social_owners o WHERE o.platform = :p AND o.external_id = :e").param("p", PLATFORM).param("e", owner).query(String.class).optional();
        if (shop.isEmpty()) {
            notify(ownerChat, "افتح الرابط من لوحة التحكم في المنصة أولاً لربط متجرك / Open the link from your dashboard first, to connect your shop.");
            return;
        }
        boolean enabled = bc.path("is_enabled").asBoolean(true);
        boolean canReply = bc.path("rights").has("can_reply") ? bc.path("rights").path("can_reply").asBoolean(true) : bc.path("can_reply").asBoolean(true);
        String name = bc.path("user").path("username").asText("").isEmpty() ? bc.path("user").path("first_name").asText("Telegram") : "@" + bc.path("user").path("username").asText();
        bridge.upsertChannel(UUID.fromString(shop.get()), null, PLATFORM, id, name, null, owner, enabled && canReply);
        if (enabled && canReply) notify(ownerChat, "✅ تم الربط، المساعد يرد الآن على عملائك / Connected: the assistant now answers your customers.");
        else if (enabled) notify(ownerChat, "⚠️ اسمح للروبوت بالرد على الرسائل من إعدادات تيليجرام للأعمال / Please allow the bot to reply to messages in Telegram Business settings.");
    }

    private void onBusinessMessage(JsonNode m) {
        String conn = m.path("business_connection_id").asText("");
        var row = bridge.channelRow(PLATFORM, conn);
        if (row == null) return;
        String from = m.path("from").path("id").asText("");
        if (from.equals(row.get("owner_ref"))) return;   // the owner typing in their own chat is not a customer
        String chat = m.path("chat").path("id").asText("");
        String text = m.path("text").asText(m.path("caption").asText(""));
        String name = (m.path("from").path("first_name").asText("") + " " + m.path("from").path("last_name").asText("")).trim();
        bridge.inbound(PLATFORM, conn, chat, conn + ":" + chat + ":" + m.path("message_id").asText(), text, name.isEmpty() ? null : name);
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void purge() { jdbc.sql("DELETE FROM chat.social_links WHERE created_at < now() - interval '1 day'").update(); }
}
