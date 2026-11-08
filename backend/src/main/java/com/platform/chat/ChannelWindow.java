package com.platform.chat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Can the shop write to this customer right now, from Elmanassa, on the chat's own channel? Each channel has its own rule (never one global 24 h rule):
 * <ul>
 * <li>Website chat: always (the message waits in the chat for when the customer looks again).</li>
 * <li>WhatsApp from the shop's own linked number: always (it is the shop's phone, no Meta window).</li>
 * <li>WhatsApp Business (Cloud API), Messenger, Instagram: only inside 24 h after the customer's last message (we stop at 23 h, the same margin the sender uses);
 * outside it the composer is closed and the shop gets the right "open on the platform" button. Free-form templates are not offered from the inbox.</li>
 * </ul>
 */
@Component
public class ChannelWindow {
    static final int SEND_HOURS = 23;

    private final JdbcClient jdbc;
    private final EvolutionService evolution;

    public ChannelWindow(JdbcClient jdbc, EvolutionService evolution) {
        this.jdbc = jdbc;
        this.evolution = evolution;
    }

    /**
     * canSend; mode WEB | OWN_NUMBER | CLOUD | MESSENGER | INSTAGRAM; endsAt (when the window closes, for a countdown) or null when there is none; reason (CLOSED /
     * NOT_CONNECTED) when it cannot send; openUrl + openKind for the "open on the platform" button, only when a real link exists.
     */
    public Map<String, Object> of(UUID tenantId, String channel, String externalId, Instant lastCustomerAt) {
        Map<String, Object> w = new LinkedHashMap<>();
        String digits = externalId == null ? "" : externalId.replaceAll("\\D", "");
        switch (channel == null ? "" : channel) {
            case "WEB" -> { w.put("canSend", true); w.put("mode", "WEB"); }
            case "WHATSAPP" -> {
                if (digits.length() >= 8) { w.put("openUrl", "https://wa.me/" + digits); w.put("openKind", "WHATSAPP"); }
                if (evolution.connected(tenantId)) { w.put("canSend", true); w.put("mode", "OWN_NUMBER"); }
                else if (!connected(tenantId, "WHATSAPP")) { w.put("canSend", false); w.put("mode", "CLOUD"); w.put("reason", "NOT_CONNECTED"); }
                else timed(w, "CLOUD", lastCustomerAt);
            }
            case "FACEBOOK", "INSTAGRAM" -> {
                w.put("openUrl", "https://business.facebook.com/latest/inbox/all");   // the page's inbox in Meta Business Suite (no valid per-chat link exists)
                w.put("openKind", channel.equals("FACEBOOK") ? "FACEBOOK_INBOX" : "INSTAGRAM_INBOX");
                if (!connected(tenantId, channel)) { w.put("canSend", false); w.put("mode", channel.equals("FACEBOOK") ? "MESSENGER" : "INSTAGRAM"); w.put("reason", "NOT_CONNECTED"); }
                else timed(w, channel.equals("FACEBOOK") ? "MESSENGER" : "INSTAGRAM", lastCustomerAt);
            }
            default -> { w.put("canSend", false); w.put("mode", "OTHER"); w.put("reason", "NOT_SUPPORTED"); }
        }
        return w;
    }

    /** True when a message can go out right now on that chat (the follow-up job's last check before sending). */
    public boolean canSend(UUID tenantId, String channel, String externalId, Instant lastCustomerAt) { return Boolean.TRUE.equals(of(tenantId, channel, externalId, lastCustomerAt).get("canSend")); }

    private void timed(Map<String, Object> w, String mode, Instant lastCustomerAt) {
        w.put("mode", mode);
        if (lastCustomerAt == null) { w.put("canSend", false); w.put("reason", "CLOSED"); return; }
        Instant ends = lastCustomerAt.plus(SEND_HOURS, ChronoUnit.HOURS);
        boolean open = Instant.now().isBefore(ends);
        w.put("canSend", open);
        w.put("endsAt", ends.toString());
        if (!open) w.put("reason", "CLOSED");
    }

    private boolean connected(UUID tenantId, String platform) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.channels WHERE tenant_id = :t AND platform = :p AND status = 'ACTIVE')").param("t", tenantId).param("p", platform).query(Boolean.class).single();
    }
}
