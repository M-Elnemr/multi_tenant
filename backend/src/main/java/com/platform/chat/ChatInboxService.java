package com.platform.chat;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The shop's inbox of customer chats with the assistant (website, WhatsApp, Messenger, Instagram): the owner reads any chat, sees which ones need them (an angry customer,
 * someone asking for a person, the assistant failing), answers in their own words, and pauses the assistant in that chat while they handle it. The assistant takes the chat back
 * when the owner presses resume, or by itself two hours after the owner's last message.
 */
@Service
public class ChatInboxService {
    static final int PAUSE_HOURS = 2;

    private final JdbcClient jdbc;
    private final ChatDelivery delivery;
    private final com.platform.audit.AuditService audit;

    public ChatInboxService(JdbcClient jdbc, ChatDelivery delivery, com.platform.audit.AuditService audit) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.audit = audit;
    }

    /** The latest chats (50), those needing the team first when {@code needsYou}; each with who, where, the last message and its state. */
    public List<Map<String, Object>> list(UUID tenantId, boolean needsYou) {
        var rows = jdbc.sql("""
                SELECT c.id, c.channel, c.external_id, c.client_id, c.display_name, c.updated_at, c.needs_human, c.needs_human_reason, c.needs_human_at, c.ai_paused_until, c.last_order,
                       (SELECT m.content FROM chat.messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message,
                       (SELECT m.role FROM chat.messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_role
                FROM chat.conversations c
                WHERE c.tenant_id = :t AND c.channel <> 'TEST' AND (NOT :n OR c.needs_human) AND EXISTS (SELECT 1 FROM chat.messages m WHERE m.conversation_id = c.id)
                ORDER BY c.needs_human DESC, c.updated_at DESC LIMIT 50
                """).param("t", tenantId).param("n", needsYou).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(view(tenantId, r));
        return out;
    }

    public long needsYouCount(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND needs_human AND channel <> 'TEST'").param("t", tenantId).query(Long.class).single();
    }

    /** One chat with its last 60 messages (oldest first). */
    public Map<String, Object> get(UUID tenantId, UUID id) {
        var r = row(tenantId, id);
        Map<String, Object> out = view(tenantId, r);
        var msgs = new ArrayList<>(Rows.camel(jdbc.sql("SELECT id, role, content, by_owner, created_at FROM chat.messages WHERE conversation_id = :c ORDER BY id DESC LIMIT 60").param("c", id).query().listOfRows()));
        java.util.Collections.reverse(msgs);
        out.put("messages", msgs);
        return out;
    }

    /** The owner's own message to the customer: saved in the chat, sent on the customer's channel, and the assistant pauses in this chat for two hours. */
    public Map<String, Object> reply(UUID tenantId, UUID actor, UUID id, String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the message");
        if (t.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message is too long (up to 2000 characters)");
        var r = row(tenantId, id);
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, by_owner) VALUES (:c, 'assistant', :x, TRUE)").param("c", id).param("x", t).update();
        jdbc.sql("UPDATE chat.conversations SET updated_at = now(), needs_human = FALSE, ai_paused_until = now() + make_interval(hours => :h) WHERE id = :c").param("h", PAUSE_HOURS).param("c", id).update();
        var sent = delivery.send(tenantId, (String) r.get("channel"), (String) r.get("external_id"), t, List.of());
        audit.record(actor, tenantId, "CHAT_OWNER_REPLY", "conversation", id, "{\"channel\":\"" + r.get("channel") + "\"}");
        Map<String, Object> out = get(tenantId, id);
        out.put("delivery", sent.delivery());
        out.put("deliveryNote", sent.note());
        return out;
    }

    /** The team takes the chat: the assistant stays quiet in it for {@code hours} (1 to 24). */
    public Map<String, Object> pause(UUID tenantId, UUID actor, UUID id, Integer hours) {
        row(tenantId, id);
        int h = hours == null ? PAUSE_HOURS : Math.max(1, Math.min(24, hours));
        jdbc.sql("UPDATE chat.conversations SET ai_paused_until = now() + make_interval(hours => :h) WHERE id = :c").param("h", h).param("c", id).update();
        audit.record(actor, tenantId, "CHAT_AI_PAUSED", "conversation", id, "{\"hours\":" + h + "}");
        return get(tenantId, id);
    }

    /** The assistant answers in this chat again; the chat no longer waits for the team. */
    public Map<String, Object> resume(UUID tenantId, UUID actor, UUID id) {
        row(tenantId, id);
        jdbc.sql("UPDATE chat.conversations SET ai_paused_until = NULL, needs_human = FALSE WHERE id = :c").param("c", id).update();
        audit.record(actor, tenantId, "CHAT_AI_RESUMED", "conversation", id, null);
        return get(tenantId, id);
    }

    /** The team looked and there is nothing to do: the chat leaves the "needs you" list. */
    public Map<String, Object> done(UUID tenantId, UUID id) {
        row(tenantId, id);
        jdbc.sql("UPDATE chat.conversations SET needs_human = FALSE WHERE id = :c").param("c", id).update();
        return get(tenantId, id);
    }

    /** A pause that ran out is cleared, so the chat shows as the assistant's again. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT3M")
    public void clearEndedPauses() { jdbc.sql("UPDATE chat.conversations SET ai_paused_until = NULL WHERE ai_paused_until < now()").update(); }

    private Map<String, Object> row(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT c.*, (SELECT m.content FROM chat.messages m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message, NULL AS last_role FROM chat.conversations c WHERE c.id = :c AND c.tenant_id = :t AND c.channel <> 'TEST'")
                .param("c", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Chat not found"));
    }

    private Map<String, Object> view(UUID tenantId, Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        String channel = (String) r.get("channel"), ext = (String) r.get("external_id");
        m.put("id", r.get("id"));
        m.put("channel", channel);
        String phone = "WHATSAPP".equals(channel) && ext != null && ext.replaceAll("\\D", "").length() >= 8 ? "+" + ext.replaceAll("\\D", "") : null;
        String name = r.get("client_id") != null
                ? jdbc.sql("SELECT name FROM commerce.customers WHERE tenant_id = :t AND user_id = :u LIMIT 1").param("t", tenantId).param("u", r.get("client_id")).query(String.class).optional().orElse(null)
                : null;
        m.put("customerName", name != null && !name.isBlank() ? name : r.get("display_name"));
        m.put("customerPhone", phone);
        m.put("lastMessage", r.get("last_message") == null ? null : clip(String.valueOf(r.get("last_message")), 160));
        m.put("lastFromCustomer", "user".equals(r.get("last_role")));
        m.put("updatedAt", r.get("updated_at"));
        m.put("needsHuman", Boolean.TRUE.equals(r.get("needs_human")));
        m.put("needsHumanReason", r.get("needs_human_reason"));
        m.put("needsHumanAt", r.get("needs_human_at"));
        Object until = r.get("ai_paused_until");
        boolean paused = until instanceof java.time.OffsetDateTime o ? o.isAfter(java.time.OffsetDateTime.now()) : until instanceof java.sql.Timestamp ts && ts.toInstant().isAfter(java.time.Instant.now());
        m.put("aiPaused", paused);
        m.put("aiPausedUntil", paused ? until : null);
        m.put("lastOrder", r.get("last_order"));
        return m;
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
}
