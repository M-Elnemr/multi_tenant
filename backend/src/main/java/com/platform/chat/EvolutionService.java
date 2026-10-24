package com.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.core.auth.LoginThrottle;
import com.platform.shared.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Optional "use my own WhatsApp number" mode. The shop owner switches it on in Settings after reading the warning, scans a QR code with the phone, and from then on the AI seller
 * answers on that number, and order confirmations and cart reminders go out as normal free messages. It is unofficial (it behaves like WhatsApp Web), so WhatsApp may ban the number:
 * it is off for every shop until the owner turns it on, sends are rate limited with a typing delay, groups and broadcasts are ignored, and it is never used for anything else.
 */
@Service
public class EvolutionService {
    private static final Logger log = LoggerFactory.getLogger(EvolutionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EVENTS = List.of("QRCODE_UPDATED", "CONNECTION_UPDATE", "MESSAGES_UPSERT");

    private final JdbcClient jdbc;
    private final EvolutionApi api;
    private final ChatService chat;
    private final WhatsAppConfirmService waConfirm;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final String baseUrl, secret, backendUrl;
    private final int typingDelayMs;
    private final ExecutorService pool;

    public EvolutionService(JdbcClient jdbc, EvolutionApi api, @Lazy ChatService chat, @Lazy WhatsAppConfirmService waConfirm, LoginThrottle throttle, AuditService audit,
                            @Value("${app.evolution.base-url:}") String baseUrl, @Value("${app.evolution.webhook-secret:}") String secret, @Value("${app.evolution.backend-url:http://backend:8080}") String backendUrl,
                            @Value("${app.evolution.typing-delay-ms:1500}") int typingDelayMs, @Value("${app.evolution.async:true}") boolean async) {
        this.jdbc = jdbc;
        this.api = api;
        this.chat = chat;
        this.waConfirm = waConfirm;
        this.throttle = throttle;
        this.audit = audit;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.backendUrl = backendUrl == null ? "" : backendUrl.trim().replaceAll("/+$", "");
        this.typingDelayMs = Math.max(0, typingDelayMs);
        this.pool = async ? Executors.newFixedThreadPool(2) : null;
    }

    /** True when this server runs an Evolution API; without one the switch in Settings is hidden. */
    public boolean available() { return !baseUrl.isBlank() && secret.length() >= 16; }

    private Map<String, Object> row(UUID tenantId) {
        return jdbc.sql("SELECT enabled, instance, status, phone, accepted_at FROM chat.evolution_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
    }

    public Map<String, Object> info(UUID tenantId) {
        var r = row(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("enabled", r != null && Boolean.TRUE.equals(r.get("enabled")));
        m.put("status", r == null ? "DISCONNECTED" : r.get("status"));
        m.put("phone", r == null ? null : r.get("phone"));
        m.put("acceptedAt", r == null ? null : r.get("accepted_at"));
        return m;
    }

    /** True when the shop turned the mode on and its phone is linked right now. */
    public boolean connected(UUID tenantId) {
        if (!available()) return false;
        var r = row(tenantId);
        return r != null && Boolean.TRUE.equals(r.get("enabled")) && "CONNECTED".equals(r.get("status"));
    }

    // ---- shop side ----------------------------------------------------------------------------------------------------------------

    public Map<String, Object> enable(UUID tenantId, UUID actor, boolean acceptedRisk) {
        if (!acceptedRisk) throw BusinessException.badRequest("RISK_NOT_ACCEPTED", "You must accept the warning first");
        if (!available()) throw BusinessException.conflict("EVOLUTION_NOT_AVAILABLE", "This feature is not available on this server");
        String instance = "shop-" + tenantId.toString().replace("-", "").substring(0, 16);
        jdbc.sql("""
                INSERT INTO chat.evolution_settings (tenant_id, enabled, instance, status, accepted_at, accepted_by) VALUES (:t, TRUE, :i, 'CONNECTING', now(), :a)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = TRUE, status = 'CONNECTING', accepted_at = now(), accepted_by = :a, updated_at = now()
                """).param("t", tenantId).param("i", instance).param("a", actor).update();
        String inst = row(tenantId).get("instance").toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceName", inst);
        body.put("integration", "WHATSAPP-BAILEYS");
        body.put("qrcode", true);
        body.put("rejectCall", false);
        body.put("groupsIgnore", true);
        body.put("alwaysOnline", false);
        body.put("readMessages", false);
        body.put("syncFullHistory", false);
        body.put("webhook", Map.of("url", backendUrl + "/api/v1/chat/evolution/webhook/" + secret, "byEvents", false, "base64", false, "events", EVENTS));
        try {
            api.call("POST", "/instance/create", body);
        } catch (EvolutionApi.EvolutionException e) {
            if (e.status() != 403 && e.status() != 409) throw unreachable(e);   // an existing instance of the shop is fine: we reconnect it below
        }
        audit.record(actor, tenantId, "EVOLUTION_ENABLED", "evolution", null, null);
        return qr(tenantId);
    }

    /** The QR code to scan from WhatsApp > Linked devices, and the current state of the link. */
    public Map<String, Object> qr(UUID tenantId) {
        var r = row(tenantId);
        if (r == null || !Boolean.TRUE.equals(r.get("enabled"))) throw BusinessException.conflict("EVOLUTION_OFF", "Turn the feature on first");
        String inst = r.get("instance").toString();
        Map<String, Object> out = new LinkedHashMap<>(refresh(tenantId));
        if ("CONNECTED".equals(out.get("status"))) return out;
        try {
            Map<String, Object> c = api.call("GET", "/instance/connect/" + inst, null);
            out.put("qr", c.get("base64"));
            out.put("pairingCode", c.get("pairingCode"));
        } catch (EvolutionApi.EvolutionException e) {
            throw unreachable(e);
        }
        return out;
    }

    /** Asks Evolution whether the phone is linked and stores the answer. */
    public Map<String, Object> refresh(UUID tenantId) {
        var r = row(tenantId);
        if (r == null) return info(tenantId);
        try {
            Map<String, Object> s = api.call("GET", "/instance/connectionState/" + r.get("instance"), null);
            Object inst = s.get("instance");
            String state = inst instanceof Map<?, ?> m ? String.valueOf(m.get("state")) : String.valueOf(s.get("state"));
            applyState(tenantId, state);
        } catch (EvolutionApi.EvolutionException e) {
            log.debug("Evolution state check failed: {}", e.getMessage());
        }
        return info(tenantId);
    }

    private void applyState(UUID tenantId, String state) {
        String st = "open".equalsIgnoreCase(state) ? "CONNECTED" : "connecting".equalsIgnoreCase(state) ? "CONNECTING" : "DISCONNECTED";
        jdbc.sql("UPDATE chat.evolution_settings SET status = :s, updated_at = now() WHERE tenant_id = :t AND enabled").param("s", st).param("t", tenantId).update();
        if ("CONNECTED".equals(st)) {
            try {
                Map<String, Object> list = api.call("GET", "/instance/fetchInstances?instanceName=" + row(tenantId).get("instance"), null);
                Object data = list.get("data");
                Object first = data instanceof List<?> l && !l.isEmpty() ? l.get(0) : list;
                String jid = first instanceof Map<?, ?> m ? String.valueOf(m.get("ownerJid") != null ? m.get("ownerJid") : m.get("number")) : null;
                if (jid != null && !"null".equals(jid)) jdbc.sql("UPDATE chat.evolution_settings SET phone = :p WHERE tenant_id = :t").param("p", jid.replaceAll("\\D", "")).param("t", tenantId).update();
            } catch (RuntimeException ignored) { /* the number is only shown to the owner */ }
        }
    }

    /** Turns the mode off: the phone is unlinked and the instance removed. Messages go back to the official channel only. */
    public Map<String, Object> disable(UUID tenantId, UUID actor) {
        var r = row(tenantId);
        if (r != null) {
            String inst = r.get("instance").toString();
            for (String path : List.of("/instance/logout/" + inst, "/instance/delete/" + inst)) {
                try { api.call("DELETE", path, null); } catch (RuntimeException e) { log.debug("Evolution cleanup {}: {}", path, e.getMessage()); }
            }
            jdbc.sql("UPDATE chat.evolution_settings SET enabled = FALSE, status = 'DISCONNECTED', phone = NULL, updated_at = now() WHERE tenant_id = :t").param("t", tenantId).update();
            audit.record(actor, tenantId, "EVOLUTION_DISABLED", "evolution", null, null);
        }
        return info(tenantId);
    }

    /** A test message from the shop's own number to a number the owner types. */
    public void test(UUID tenantId, String number, String text) {
        if (!connected(tenantId)) throw BusinessException.conflict("EVOLUTION_OFF", "Link your phone first");
        String n = number == null ? "" : number.replaceAll("\\D", "");
        if (n.length() < 8 || n.length() > 15) throw BusinessException.badRequest("VALIDATION_ERROR", "Type the number with its country code");
        if (!sendFree(tenantId, n, text == null || text.isBlank() ? "✅ رسالة تجريبية من متجرك / Test message from your shop" : text.trim())) throw BusinessException.conflict("EVOLUTION_SEND_FAILED", "The message could not be sent");
    }

    // ---- sending --------------------------------------------------------------------------------------------------------------------

    /** Sends a normal text from the shop's own number. False when the mode is off, the phone is not linked, the shop is sending too fast, or the send failed. Never throws. */
    public boolean sendFree(UUID tenantId, String number, String text) {
        try {
            if (!connected(tenantId) || text == null || text.isBlank()) return false;
            throttle.check("evo-out|" + tenantId, 20, Duration.ofMinutes(1));   // a person does not send faster; a ban is likelier when a number does
            String n = number.replaceAll("\\D", "");
            api.call("POST", "/message/sendText/" + row(tenantId).get("instance"), Map.of("number", n, "text", text, "delay", typingDelayMs));
            return true;
        } catch (RuntimeException e) {
            log.warn("Unofficial WhatsApp send failed: {}", e.getMessage());
            return false;
        }
    }

    // ---- Evolution calls us ----------------------------------------------------------------------------------------------------------

    public boolean validSecret(String given) {
        return available() && given != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    public void handle(String raw) {
        if (pool != null) pool.execute(() -> handleNow(raw)); else handleNow(raw);
    }

    void handleNow(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            String instance = root.path("instance").asText("");
            var tenant = jdbc.sql("SELECT tenant_id FROM chat.evolution_settings WHERE instance = :i AND enabled").param("i", instance).query(UUID.class).optional();
            if (tenant.isEmpty()) return;
            UUID tenantId = tenant.get();
            String event = root.path("event").asText("").toLowerCase().replace('_', '.');
            JsonNode data = root.path("data");
            if (event.equals("connection.update")) { applyState(tenantId, data.path("state").asText("")); return; }
            if (!event.equals("messages.upsert")) return;
            for (JsonNode m : data.isArray() ? data : List.of(data)) message(tenantId, m);
        } catch (Exception e) {
            log.warn("Could not handle an Evolution event: {}", e.toString());
        }
    }

    private void message(UUID tenantId, JsonNode m) {
        JsonNode key = m.path("key");
        if (key.path("fromMe").asBoolean(false)) return;
        String jid = key.path("remoteJid").asText("");
        if (jid.endsWith("@lid")) jid = key.path("remoteJidAlt").asText(key.path("senderPn").asText(jid));   // newer WhatsApp ids: the phone number is in the alternate field
        if (!jid.endsWith("@s.whatsapp.net")) return;   // groups, broadcasts and status updates are never answered
        String sender = jid.replaceAll("\\D", "");
        String id = key.path("id").asText("");
        if (sender.length() < 8 || id.isEmpty()) return;
        if (jdbc.sql("INSERT INTO chat.meta_events (mid) VALUES (:m) ON CONFLICT DO NOTHING").param("m", "evo:" + id).update() == 0) return;
        JsonNode msg = m.path("message");
        String text = msg.hasNonNull("conversation") ? msg.path("conversation").asText() : msg.path("extendedTextMessage").hasNonNull("text") ? msg.path("extendedTextMessage").path("text").asText() : null;
        boolean aiOn = chat.enabled(tenantId);
        String answer;
        try {
            if (text == null || text.isBlank()) {
                if (!aiOn) return;
                answer = "أستطيع قراءة الرسائل النصية فقط، اكتب لي ما تحتاجه 🙏 / I can only read text messages, please type what you need.";
            } else {
                throttle.check("chat-evo|" + tenantId + "|" + sender, 40, Duration.ofMinutes(10));
                var special = waConfirm.handle(tenantId, sender, text);
                if (special.isPresent()) answer = special.get();
                else if (!aiOn) return;
                else answer = chat.reply(tenantId, "WHATSAPP", sender, null, text.length() > 1000 ? text.substring(0, 1000) : text).text();
            }
        } catch (BusinessException e) {
            log.debug("Evolution message ignored: {}", e.getMessage());
            return;
        }
        for (String part : MetaChannelService.chunks(answer, 3000)) sendFree(tenantId, sender, part);
    }

    private static BusinessException unreachable(EvolutionApi.EvolutionException e) {
        return BusinessException.conflict("EVOLUTION_UNREACHABLE", "The WhatsApp link service did not answer: " + e.getMessage());
    }
}
