package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Facebook Messenger, Instagram and WhatsApp for the AI seller. A shop connects its own page / account / number with an access token; Meta calls the
 * single webhook below for every connected shop, the message is matched to the shop by page id (or phone-number id), answered by {@link ChatService}
 * and sent back through the same channel. Works in Meta's development mode for the app owner's own accounts; the production flow (Facebook Login for
 * Business, Embedded Signup) only changes how the token is obtained.
 */
@Service
public class MetaChannelService {
    private static final Logger log = LoggerFactory.getLogger(MetaChannelService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PLATFORMS = List.of("FACEBOOK", "INSTAGRAM", "WHATSAPP");

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final TokenCrypto crypto;
    private final ChatService chat;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final WhatsAppConfirmService waConfirm;
    private final String appSecret;
    private final String verifyToken;
    private final ExecutorService pool;
    private final java.util.concurrent.ScheduledExecutorService holder = Executors.newSingleThreadScheduledExecutor();

    public MetaChannelService(JdbcClient jdbc, MetaGraph graph, TokenCrypto crypto, ChatService chat, LoginThrottle throttle, AuditService audit, WhatsAppConfirmService waConfirm,
                              @Value("${app.meta.app-secret:}") String appSecret, @Value("${app.meta.verify-token:}") String verifyToken, @Value("${app.meta.async:true}") boolean async) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.crypto = crypto;
        this.chat = chat;
        this.throttle = throttle;
        this.audit = audit;
        this.waConfirm = waConfirm;
        this.appSecret = appSecret == null || appSecret.isBlank() ? null : appSecret.trim();
        this.verifyToken = verifyToken == null || verifyToken.isBlank() ? null : verifyToken.trim();
        this.pool = async ? Executors.newFixedThreadPool(4) : null;
    }

    public boolean configured() { return appSecret != null && verifyToken != null; }

    public String verifyToken() { return verifyToken; }

    // ---- shop side: connect / list / disconnect ---------------------------------------------------------------

    public List<Map<String, Object>> channels(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, platform, external_id, name, status, created_at FROM chat.channels WHERE tenant_id = :t ORDER BY created_at").param("t", tenantId).query().listOfRows());
    }

    public Map<String, Object> connect(UUID tenantId, UUID actor, String platform, String externalId, String accessToken, String pageId) {
        String p = platform == null ? "" : platform.toUpperCase();
        if (!PLATFORMS.contains(p)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown platform");
        String ext = externalId == null ? "" : externalId.trim();
        String token = accessToken == null ? "" : accessToken.trim();
        if (!ext.matches("\\d{5,40}")) throw BusinessException.badRequest("VALIDATION_ERROR", "The id must be the number from Meta");
        if (token.length() < 20 || token.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The access token looks wrong");
        String name;
        String phone = null;
        try {
            Map<String, Object> info = graph.get(ext + ("WHATSAPP".equals(p) ? "?fields=verified_name,display_phone_number" : "?fields=id,name,username"), token);
            name = String.valueOf(info.getOrDefault("verified_name", info.getOrDefault("name", info.getOrDefault("username", ext))));
            if ("WHATSAPP".equals(p) && info.get("display_phone_number") != null) { phone = String.valueOf(info.get("display_phone_number")); name += " " + phone; }
            String subscribeTo = "FACEBOOK".equals(p) ? ext : (pageId == null || pageId.isBlank() ? null : pageId.trim());   // Instagram: its linked page; WhatsApp: its business account
            if (subscribeTo != null) {
                try { graph.post(subscribeTo + "/subscribed_apps", token, "WHATSAPP".equals(p) ? Map.of() : Map.of("subscribed_fields", "messages")); } catch (MetaGraph.MetaException e) { log.warn("Could not subscribe {} to webhooks: {}", subscribeTo, e.getMessage()); }
            }
        } catch (MetaGraph.MetaException e) {
            throw BusinessException.badRequest("META_REJECTED", "Meta did not accept these details: " + e.getMessage());
        }
        var owner = jdbc.sql("SELECT tenant_id FROM chat.channels WHERE platform = :p AND external_id = :e").param("p", p).param("e", ext).query(UUID.class).optional();
        if (owner.isPresent() && !owner.get().equals(tenantId)) throw BusinessException.conflict("CHANNEL_TAKEN", "This account is already connected to another shop");
        UUID id = jdbc.sql("""
                INSERT INTO chat.channels (tenant_id, platform, external_id, name, token_enc, phone, waba_id) VALUES (:t, :p, :e, :n, :k, :ph, :w)
                ON CONFLICT (platform, external_id) DO UPDATE SET name = :n, token_enc = :k, phone = coalesce(:ph, chat.channels.phone), waba_id = coalesce(:w, chat.channels.waba_id), status = 'ACTIVE' RETURNING id
                """).param("t", tenantId).param("p", p).param("e", ext).param("n", name).param("k", crypto.encrypt(token)).param("ph", phone)
                .param("w", "WHATSAPP".equals(p) && pageId != null && pageId.trim().matches("\\d{5,40}") ? pageId.trim() : null).query(UUID.class).single();
        audit.record(actor, tenantId, "AI_CHANNEL_CONNECTED", "ai_channel", id, "{\"platform\":\"" + p + "\"}");
        return channels(tenantId).stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
    }

    public void disconnect(UUID tenantId, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM chat.channels WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "AI_CHANNEL_DISCONNECTED", "ai_channel", id, null);
    }

    // ---- webhook ---------------------------------------------------------------------------------------------

    /** True when the body was signed with our app secret (constant-time compare). */
    public boolean validSignature(String raw, String header) {
        if (appSecret == null || header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8), header.substring(7).toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called after the signature check. Answers Meta at once; the AI work runs in the background. */
    public void handle(String raw) {
        Runnable task = () -> {
            try { process(JSON.readTree(raw)); } catch (Exception e) { log.warn("Meta webhook processing failed", e); }
        };
        if (pool != null) pool.submit(task); else task.run();
    }

    private void process(JsonNode root) {
        String object = root.path("object").asText();
        for (JsonNode entry : root.path("entry")) {
            if ("whatsapp_business_account".equals(object)) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode v = change.path("value");
                    String pnid = v.path("metadata").path("phone_number_id").asText("");
                    for (JsonNode m : v.path("messages"))
                        inbound("WHATSAPP", pnid, m.path("from").asText(""), m.path("id").asText(""), whatsappText(m));
                }
            } else if ("page".equals(object) || "instagram".equals(object)) {
                String platform = "page".equals(object) ? "FACEBOOK" : "INSTAGRAM";
                String accountId = entry.path("id").asText("");
                for (JsonNode ev : entry.path("messaging")) {
                    JsonNode msg = ev.path("message");
                    String sender = ev.path("sender").path("id").asText("");
                    if (msg.isMissingNode() || msg.path("is_echo").asBoolean(false) || sender.equals(accountId)) continue;
                    inbound(platform, accountId, sender, msg.path("mid").asText(""), msg.hasNonNull("text") ? msg.path("text").asText("") : null);
                }
            }
        }
    }

    /** The text of a WhatsApp message: typed text, or the payload of a tapped template button / interactive reply (that is how "Confirm" arrives). */
    private static String whatsappText(JsonNode m) {
        return switch (m.path("type").asText()) {
            case "text" -> m.path("text").path("body").asText("");
            case "button" -> m.path("button").path("payload").asText(m.path("button").path("text").asText(""));
            case "interactive" -> m.path("interactive").path("button_reply").path("id").asText(m.path("interactive").path("button_reply").path("title").asText(""));
            default -> null;
        };
    }

    /** The shop's active WhatsApp channel with its decrypted token, or null. */
    public WaChannel whatsappChannel(UUID tenantId) {
        var r = jdbc.sql("SELECT external_id, token_enc, waba_id, phone FROM chat.channels WHERE tenant_id = :t AND platform = 'WHATSAPP' AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        return r == null ? null : new WaChannel((String) r.get("external_id"), crypto.decrypt((String) r.get("token_enc")), (String) r.get("waba_id"), (String) r.get("phone"));
    }

    public record WaChannel(String phoneId, String token, String wabaId, String phone) {}

    private void inbound(String platform, String accountId, String sender, String mid, String text) {
        if (accountId.isEmpty() || sender.isEmpty() || mid.isEmpty()) return;
        var ch = jdbc.sql("SELECT tenant_id, token_enc, status FROM chat.channels WHERE platform = :p AND external_id = :e").param("p", platform).param("e", accountId).query().listOfRows().stream().findFirst();
        if (ch.isEmpty() || !"ACTIVE".equals(ch.get().get("status"))) return;
        if (jdbc.sql("INSERT INTO chat.meta_events (mid) VALUES (:m) ON CONFLICT DO NOTHING").param("m", mid).update() == 0) return;   // already handled
        UUID tenantId = (UUID) ch.get().get("tenant_id");
        String token = crypto.decrypt((String) ch.get().get("token_enc"));
        if ("WHATSAPP".equals(platform)) jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now()) ON CONFLICT (tenant_id, number) DO UPDATE SET last_inbound_at = now()")
                .param("t", tenantId).param("n", sender.replaceAll("\\D", "")).update();
        boolean aiOn = chat.enabled(tenantId);
        String answer;
        if (text == null || text.isBlank()) {
            if (!aiOn) return;
            answer = "أستطيع قراءة الرسائل النصية فقط، اكتب لي ما تحتاجه 🙏 / I can only read text messages, please type what you need.";
        } else {
            try {
                throttle.check("chat-meta|" + platform + "|" + sender, 40, Duration.ofMinutes(10));
                var special = "WHATSAPP".equals(platform) ? waConfirm.handle(tenantId, sender, text) : java.util.Optional.<String>empty();
                if (special.isPresent()) {
                    answer = special.get();
                } else if (!aiOn) {
                    return;   // order confirmations above keep working when the assistant is off; free chat does not
                } else {
                    // A search or a long answer can take a while: after a few seconds tell the customer we are still on it.
                    boolean arabic = text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
                    var hold = holder.schedule(() -> {
                        try { send(platform, accountId, sender, token, arabic ? "لحظات معايا... 🌸" : "One moment, I am checking for you... 🌸"); } catch (RuntimeException ignored) { /* best effort */ }
                    }, 5, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        var reply = chat.reply(tenantId, platform, sender, null, text.length() > 1000 ? text.substring(0, 1000) : text);
                        answer = reply.text();
                        if (reply.order() != null && !Boolean.TRUE.equals(reply.order().get("test"))) answer += orderLinks(tenantId, reply.order());
                    } finally {
                        hold.cancel(false);
                    }
                }
            } catch (BusinessException e) {
                log.debug("Meta message ignored: {}", e.getMessage());
                return;
            }
        }
        try {
            for (String part : chunks(answer, "INSTAGRAM".equals(platform) ? 900 : "WHATSAPP".equals(platform) ? 3500 : 1900)) send(platform, accountId, sender, token, part);
        } catch (MetaGraph.MetaException e) {
            log.warn("Could not send the {} reply: {}", platform, e.getMessage());
        }
    }

    void send(String platform, String accountId, String to, String token, String text) {
        if ("WHATSAPP".equals(platform)) graph.post(accountId + "/messages", token, Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text)));
        else graph.post("me/messages", token, Map.of("recipient", Map.of("id", to), "messaging_type", "RESPONSE", "message", Map.of("text", text)));
    }

    /** Links the customer needs outside the website: where to track the order, and where to pay when it is by card. */
    private String orderLinks(UUID tenantId, Map<String, Object> order) {
        String host = jdbc.sql("SELECT host FROM core.tenant_domains WHERE tenant_id = :t ORDER BY is_primary DESC, created_at LIMIT 1").param("t", tenantId).query(String.class).optional().orElse(null);
        if (host == null) return "";
        StringBuilder b = new StringBuilder();
        if (order.get("trackPath") != null) b.append("\n\n🔎 https://").append(host).append(order.get("trackPath"));
        if (order.get("paymentPath") != null) b.append("\n💳 https://").append(host).append(order.get("paymentPath"));
        return b.toString();
    }

    static List<String> chunks(String s, int max) {
        List<String> out = new ArrayList<>();
        String rest = s == null ? "" : s.trim();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max);
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max);
            if (cut < max / 2) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purgeEvents() { jdbc.sql("DELETE FROM chat.meta_events WHERE created_at < now() - interval '3 days'").update(); }
}
