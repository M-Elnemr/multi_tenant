package com.platform.chat;

import com.platform.shared.BusinessException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The platform's own WhatsApp number, linked by the platform admin with a QR code (free, unofficial, like a shop's own number). It only sends one-time codes
 * so people who register on the website or the app prove that the phone number is theirs. It never answers anyone and never sends marketing.
 */
@Service
public class PlatformWhatsApp {
    private static final Logger log = LoggerFactory.getLogger(PlatformWhatsApp.class);
    static final String INSTANCE = "platform-main";

    private final JdbcClient jdbc;
    private final EvolutionApi api;
    private final com.platform.core.auth.LoginThrottle throttle;
    private final String baseUrl;

    public PlatformWhatsApp(JdbcClient jdbc, EvolutionApi api, com.platform.core.auth.LoginThrottle throttle, @Value("${app.evolution.base-url:}") String baseUrl) {
        this.jdbc = jdbc;
        this.api = api;
        this.throttle = throttle;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public boolean available() { return !baseUrl.isBlank(); }

    private Map<String, Object> row() { return jdbc.sql("SELECT status, phone FROM chat.platform_whatsapp WHERE id = 1").query().listOfRows().stream().findFirst().orElse(null); }

    public boolean connected() { var r = row(); return available() && r != null && "CONNECTED".equals(r.get("status")); }

    public Map<String, Object> info() {
        var r = row();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("enabled", r != null);
        m.put("status", r == null ? "DISCONNECTED" : r.get("status"));
        m.put("phone", r == null ? null : r.get("phone"));
        return m;
    }

    public Map<String, Object> enable() {
        if (!available()) throw BusinessException.conflict("EVOLUTION_NOT_AVAILABLE", "This feature is not available on this server");
        jdbc.sql("INSERT INTO chat.platform_whatsapp (id, instance, status) VALUES (1, :i, 'CONNECTING') ON CONFLICT (id) DO UPDATE SET status = 'CONNECTING', updated_at = now()").param("i", INSTANCE).update();
        try {
            api.call("POST", "/instance/create", Map.of("instanceName", INSTANCE, "integration", "WHATSAPP-BAILEYS", "qrcode", true, "groupsIgnore", true, "alwaysOnline", false, "readMessages", false, "syncFullHistory", false));
        } catch (EvolutionApi.EvolutionException e) {
            if (e.status() != 403 && e.status() != 409) throw BusinessException.conflict("EVOLUTION_UNREACHABLE", "The WhatsApp link service did not answer: " + e.getMessage());
        }
        return qr();
    }

    public Map<String, Object> qr() {
        if (row() == null) throw BusinessException.conflict("EVOLUTION_OFF", "Turn the feature on first");
        Map<String, Object> out = new LinkedHashMap<>(refresh());
        if ("CONNECTED".equals(out.get("status"))) return out;
        try {
            Map<String, Object> c = api.call("GET", "/instance/connect/" + INSTANCE, null);
            out.put("qr", c.get("base64"));
            out.put("pairingCode", c.get("pairingCode"));
        } catch (EvolutionApi.EvolutionException e) {
            throw BusinessException.conflict("EVOLUTION_UNREACHABLE", "The WhatsApp link service did not answer: " + e.getMessage());
        }
        return out;
    }

    public Map<String, Object> refresh() {
        if (row() == null) return info();
        try {
            Map<String, Object> s = api.call("GET", "/instance/connectionState/" + INSTANCE, null);
            Object inst = s.get("instance");
            String state = inst instanceof Map<?, ?> m ? String.valueOf(m.get("state")) : String.valueOf(s.get("state"));
            String st = "open".equalsIgnoreCase(state) ? "CONNECTED" : "connecting".equalsIgnoreCase(state) ? "CONNECTING" : "DISCONNECTED";
            jdbc.sql("UPDATE chat.platform_whatsapp SET status = :s, updated_at = now() WHERE id = 1").param("s", st).update();
            if ("CONNECTED".equals(st)) {
                try {
                    Map<String, Object> list = api.call("GET", "/instance/fetchInstances?instanceName=" + INSTANCE, null);
                    Object data = list.get("data");
                    Object first = data instanceof List<?> l && !l.isEmpty() ? l.get(0) : list;
                    String jid = first instanceof Map<?, ?> m ? String.valueOf(m.get("ownerJid") != null ? m.get("ownerJid") : m.get("number")) : null;
                    if (jid != null && !"null".equals(jid)) jdbc.sql("UPDATE chat.platform_whatsapp SET phone = :p WHERE id = 1").param("p", jid.replaceAll("\\D", "")).update();
                } catch (RuntimeException ignored) { /* the number is only shown to the admin */ }
            }
        } catch (EvolutionApi.EvolutionException e) {
            log.debug("Platform WhatsApp state check failed: {}", e.getMessage());
        }
        return info();
    }

    public Map<String, Object> disable() {
        if (row() != null) {
            for (String path : List.of("/instance/logout/" + INSTANCE, "/instance/delete/" + INSTANCE)) {
                try { api.call("DELETE", path, null); } catch (RuntimeException e) { log.debug("Platform WhatsApp cleanup {}: {}", path, e.getMessage()); }
            }
            jdbc.sql("DELETE FROM chat.platform_whatsapp WHERE id = 1").update();
        }
        return info();
    }

    /** Sends one message from the platform number. False when it is not linked, over its rate limit, or the send failed. Never throws. */
    public boolean send(String number, String text) {
        try {
            if (!connected() || text == null || text.isBlank()) return false;
            throttle.check("platform-wa-out", 40, Duration.ofMinutes(1));
            api.call("POST", "/message/sendText/" + INSTANCE, Map.of("number", number.replaceAll("\\D", ""), "text", text, "delay", 800));
            return true;
        } catch (RuntimeException e) {
            log.warn("Platform WhatsApp send failed: {}", e.getMessage());
            return false;
        }
    }
}
