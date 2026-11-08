package com.platform.chat;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Free automatic follow-ups for customers who stopped answering: up to five messages, each a set time after the customer's last message (30 minutes to 23 hours),
 * all inside the channel's 24 h window and each only while the customer still has not answered.
 * <ul>
 * <li>The steps are scheduled by the database itself when the AI or the team answers a customer (see V81 chat.on_message_insert), once per quiet period
 * (unique per conversation, customer message and step), so nothing is ever scheduled twice; a customer message cancels the steps still waiting.</li>
 * <li>Right before sending, everything is checked again: the chat is open, nobody took it over and the AI is not paused, the customer still has not written, they did
 * not ask to stop messages, the earlier steps went out, and the channel still allows a message (the 24 h window, the connected number).</li>
 * <li>No Elmanassa charge (no wallet debit). A provider failure is retried once after 5 minutes, then logged as FAILED.</li>
 * </ul>
 */
@Service
public class FollowUpService {
    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
    static final int MAX_STEPS = 5, MIN_DELAY = 30, MAX_DELAY = 23 * 60;

    private final JdbcClient jdbc;
    private final ChatDelivery delivery;
    private final ChannelWindow window;
    private final ChatTimeline timeline;
    private final TransactionTemplate tx;
    private final boolean scheduler;

    public FollowUpService(JdbcClient jdbc, ChatDelivery delivery, ChannelWindow window, ChatTimeline timeline, TransactionTemplate tx,
                           @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.window = window;
        this.timeline = timeline;
        this.tx = tx;
        this.scheduler = scheduler;
    }

    // ---- settings ---------------------------------------------------------------------------------------------------------

    public record Step(Integer position, Integer delayMinutes, String message, Boolean enabled) {}

    public Map<String, Object> settings(UUID tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", jdbc.sql("SELECT enabled FROM chat.followup_settings WHERE tenant_id = :t").param("t", tenantId).query(Boolean.class).optional().orElse(false));
        m.put("steps", Rows.camel(jdbc.sql("SELECT position, delay_minutes, message, enabled FROM chat.followup_steps WHERE tenant_id = :t ORDER BY position").param("t", tenantId).query().listOfRows()));
        m.put("minDelayMinutes", MIN_DELAY);
        m.put("maxDelayMinutes", MAX_DELAY);
        m.put("maxSteps", MAX_STEPS);
        return m;
    }

    /** Replaces the whole sequence: 1 to 5 steps, delays strictly increasing between 30 minutes and 23 hours, each with a message. Turning it off cancels what is waiting. */
    public Map<String, Object> save(UUID tenantId, UUID actor, Boolean enabled, List<Step> steps) {
        List<Step> list = steps == null ? List.of() : steps;
        if (list.size() > MAX_STEPS) throw BusinessException.badRequest("VALIDATION_ERROR", "Up to 5 follow-up messages");
        if (Boolean.TRUE.equals(enabled) && list.stream().noneMatch(s -> !Boolean.FALSE.equals(s.enabled()))) throw BusinessException.badRequest("VALIDATION_ERROR", "Add at least one follow-up message");
        int prev = 0;
        for (Step s : list) {
            if (s.delayMinutes() == null || s.delayMinutes() < MIN_DELAY || s.delayMinutes() > MAX_DELAY) throw BusinessException.badRequest("VALIDATION_ERROR", "Each follow-up is sent 30 minutes to 23 hours after the customer's last message");
            if (s.delayMinutes() <= prev) throw BusinessException.badRequest("VALIDATION_ERROR", "Each follow-up must come later than the one before it");
            prev = s.delayMinutes();
            String msg = s.message() == null ? "" : s.message().trim();
            if (msg.isEmpty() || msg.length() > 600) throw BusinessException.badRequest("VALIDATION_ERROR", "Write each follow-up message (up to 600 characters)");
        }
        tx.executeWithoutResult(st -> {
            jdbc.sql("INSERT INTO chat.followup_settings (tenant_id, enabled) VALUES (:t, :e) ON CONFLICT (tenant_id) DO UPDATE SET enabled = :e, updated_at = now()")
                    .param("t", tenantId).param("e", Boolean.TRUE.equals(enabled)).update();
            jdbc.sql("DELETE FROM chat.followup_steps WHERE tenant_id = :t").param("t", tenantId).update();
            int pos = 1;
            for (Step s : list) jdbc.sql("INSERT INTO chat.followup_steps (tenant_id, position, delay_minutes, message, enabled) VALUES (:t, :p, :d, :m, :e)")
                    .param("t", tenantId).param("p", pos++).param("d", s.delayMinutes()).param("m", s.message().trim()).param("e", !Boolean.FALSE.equals(s.enabled())).update();
            if (!Boolean.TRUE.equals(enabled))
                jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'TURNED_OFF' WHERE tenant_id = :t AND status = 'SCHEDULED'").param("t", tenantId).update();
        });
        return settings(tenantId);
    }

    // ---- management ---------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> list(UUID tenantId, String status) {
        String st = status == null || status.isBlank() ? null : status.toUpperCase(java.util.Locale.ROOT);
        if (st != null && !List.of("SCHEDULED", "SENT", "CANCELLED", "FAILED", "INELIGIBLE").contains(st)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status");
        return Rows.camel(jdbc.sql("""
                SELECT f.id, f.conversation_id, f.step_position, f.due_at, f.status, f.reason, f.body, f.attempts, f.sent_at, f.returned_at, f.created_at,
                       c.channel, c.display_name, c.external_id
                FROM chat.auto_followups f JOIN chat.conversations c ON c.id = f.conversation_id
                WHERE f.tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR f.status = CAST(:s AS varchar))
                ORDER BY CASE WHEN f.status = 'SCHEDULED' THEN f.due_at END ASC NULLS LAST, f.created_at DESC LIMIT 200
                """).param("t", tenantId).param("s", st).query().listOfRows());
    }

    /** Totals of the last 30 days; {@code returned}: follow-ups after which the customer wrote again within 24 h (measured from the real timestamps). */
    public Map<String, Object> stats(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'SCHEDULED') AS scheduled, count(*) FILTER (WHERE status = 'SENT') AS sent,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled, count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'INELIGIBLE') AS ineligible, count(*) FILTER (WHERE status = 'SENT' AND returned_at IS NOT NULL) AS returned,
                       count(DISTINCT conversation_id) FILTER (WHERE status = 'SCHEDULED') AS waiting
                FROM chat.auto_followups WHERE tenant_id = :t AND created_at > now() - interval '30 days'
                """).param("t", tenantId).query().singleRow();
        return Rows.camel(r);
    }

    public void cancel(UUID tenantId, UUID actor, UUID id) {
        var conv = jdbc.sql("UPDATE chat.auto_followups SET status = 'CANCELLED', reason = 'OWNER' WHERE id = :i AND tenant_id = :t AND status = 'SCHEDULED' RETURNING conversation_id")
                .param("i", id).param("t", tenantId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.conflict("NOT_SCHEDULED", "This follow-up is not waiting to be sent"));
        timeline.event(tenantId, conv, "FOLLOWUP_CANCELLED", actor, "{\"reason\":\"OWNER\"}");
    }

    public void edit(UUID tenantId, UUID id, String body) {
        String b = body == null ? "" : body.trim();
        if (b.isEmpty() || b.length() > 600) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the follow-up message (up to 600 characters)");
        if (jdbc.sql("UPDATE chat.auto_followups SET body = :b WHERE id = :i AND tenant_id = :t AND status = 'SCHEDULED'").param("b", b).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.conflict("NOT_SCHEDULED", "This follow-up is not waiting to be sent");
    }

    // ---- the sender ---------------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT90S")
    public void job() { if (scheduler) runDue(); }

    /** Sends every follow-up that is due (one instance at a time, each row locked while it is handled). Returns how many were sent. */
    public int runDue() {
        Integer sent = tx.execute(st -> {
            if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(81081)").query(Boolean.class).single()) return 0;
            int n = 0;
            var due = jdbc.sql("""
                    SELECT f.id FROM chat.auto_followups f WHERE f.status = 'SCHEDULED' AND f.due_at <= now()
                    ORDER BY f.due_at LIMIT 100 FOR UPDATE SKIP LOCKED
                    """).query(UUID.class).list();
            for (UUID id : due) if (sendOne(id)) n++;
            return n;
        });
        return sent == null ? 0 : sent;
    }

    private boolean sendOne(UUID id) {
        var f = jdbc.sql("""
                SELECT f.*, c.channel, c.external_id, c.client_id, c.contact_phone, c.status AS conv_status, c.takeover, c.ai_paused_until, c.needs_human, c.last_customer_at, c.display_name,
                       tn.status AS tenant_status
                FROM chat.auto_followups f JOIN chat.conversations c ON c.id = f.conversation_id JOIN core.tenants tn ON tn.id = f.tenant_id WHERE f.id = :i
                """).param("i", id).query().singleRow();
        UUID tenantId = (UUID) f.get("tenant_id"), conv = (UUID) f.get("conversation_id");
        int step = ((Number) f.get("step_position")).intValue();
        Instant anchor = ts(f.get("anchor_customer_at")), lastCustomer = ts(f.get("last_customer_at"));
        String skip = null;
        if (!List.of("ACTIVE", "TRIAL", "PAST_DUE").contains(String.valueOf(f.get("tenant_status")))) skip = "SHOP_NOT_ACTIVE";
        else if (!jdbc.sql("SELECT enabled FROM chat.followup_settings WHERE tenant_id = :t").param("t", tenantId).query(Boolean.class).optional().orElse(false)) skip = "TURNED_OFF";
        else if (!"OPEN".equals(f.get("conv_status"))) skip = "CLOSED";
        else if (Boolean.TRUE.equals(f.get("takeover"))) skip = "TAKEOVER";
        else if (ts(f.get("ai_paused_until")) != null && ts(f.get("ai_paused_until")).isAfter(Instant.now())) skip = "PAUSED";
        else if (Boolean.TRUE.equals(f.get("needs_human"))) skip = "NEEDS_TEAM";
        else if (lastCustomer == null || anchor == null || lastCustomer.isAfter(anchor.plusMillis(1))) skip = "CUSTOMER_REPLIED";
        else if (Instant.now().isAfter(anchor.plusSeconds(ChannelWindow.SEND_HOURS * 3600L))) skip = "OUTSIDE_WINDOW";
        else if (optedOut(tenantId, (String) f.get("channel"), (String) f.get("external_id"), (UUID) f.get("client_id"), (String) f.get("contact_phone"))) skip = "OPTED_OUT";
        else if (step > 1) {
            var earlier = jdbc.sql("SELECT status FROM chat.auto_followups WHERE conversation_id = :c AND anchor_customer_at = :a AND step_position < :p")
                    .param("c", conv).param("a", java.sql.Timestamp.from(anchor)).param("p", step).query(String.class).list();
            if (earlier.contains("SCHEDULED")) return false;   // the step before is still being retried: wait for it
            if (earlier.stream().anyMatch(x -> !"SENT".equals(x) && !"INELIGIBLE".equals(x))) skip = "EARLIER_STEP_NOT_SENT";
        }
        if (skip == null && !window.canSend(tenantId, (String) f.get("channel"), (String) f.get("external_id"), lastCustomer)) skip = "OUTSIDE_WINDOW";
        if (skip != null) {
            String st = "OUTSIDE_WINDOW".equals(skip) || "OPTED_OUT".equals(skip) || "SHOP_NOT_ACTIVE".equals(skip) ? "INELIGIBLE" : "CANCELLED";
            jdbc.sql("UPDATE chat.auto_followups SET status = :s, reason = :r WHERE id = :i").param("s", st).param("r", skip).param("i", id).update();
            return false;
        }
        String body = personal(tenantId, (String) f.get("body"), (String) f.get("display_name"), (UUID) f.get("client_id"));
        ChatDelivery.Result res;
        try { res = delivery.send(tenantId, (String) f.get("channel"), (String) f.get("external_id"), body, List.of()); }
        catch (RuntimeException e) { res = new ChatDelivery.Result("FAILED", e.getMessage()); }
        int attempts = ((Number) f.get("attempts")).intValue() + 1;
        if ("FAILED".equals(res.delivery())) {
            boolean retry = attempts < 2;
            jdbc.sql("UPDATE chat.auto_followups SET attempts = :n, status = :s, reason = :r, due_at = CASE WHEN :retry THEN now() + interval '5 minutes' ELSE due_at END WHERE id = :i")
                    .param("n", attempts).param("s", retry ? "SCHEDULED" : "FAILED").param("r", clip(res.note() == null ? "PROVIDER_FAILED" : res.note(), 200)).param("retry", retry).param("i", id).update();
            if (!retry) timeline.event(tenantId, conv, "FOLLOWUP_FAILED", null, "{\"step\":" + step + "}");
            log.info("Follow-up {} step {} failed ({}): {}", conv, step, retry ? "will retry" : "gave up", res.note());
            return false;
        }
        jdbc.sql("INSERT INTO chat.messages (conversation_id, role, content, kind) VALUES (:c, 'assistant', :x, 'FOLLOWUP')").param("c", conv).param("x", body).update();
        jdbc.sql("UPDATE chat.auto_followups SET status = 'SENT', sent_at = now(), attempts = :n, reason = NULL WHERE id = :i").param("n", attempts).param("i", id).update();
        jdbc.sql("UPDATE chat.conversations SET updated_at = now() WHERE id = :c").param("c", conv).update();
        timeline.event(tenantId, conv, "FOLLOWUP_SENT", null, "{\"step\":" + step + "}");
        return true;
    }

    /** The customer asked the shop to stop messages (the same «stop» list the broadcasts respect). */
    private boolean optedOut(UUID tenantId, String channel, String externalId, UUID clientId, String contactPhone) {
        String phone = "WHATSAPP".equals(channel) ? externalId : contactPhone;
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM commerce.customers cu WHERE cu.tenant_id = :t AND cu.broadcast_opt_out_at IS NOT NULL
                  AND ((CAST(:u AS uuid) IS NOT NULL AND cu.user_id = :u) OR (length(:d) >= 9 AND right(regexp_replace(coalesce(cu.phone, ''), '\\D', '', 'g'), 9) = right(:d, 9))))
                """).param("t", tenantId).param("u", clientId).param("d", d).query(Boolean.class).single();
    }

    /** {name} and {shop} in the message. */
    private String personal(UUID tenantId, String body, String displayName, UUID clientId) {
        if (body == null || !body.contains("{")) return body;
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        String name = clientId == null ? null : jdbc.sql("SELECT name FROM commerce.client_accounts WHERE id = :u").param("u", clientId).query(String.class).optional().orElse(null);
        String first = ChatService.firstName(name != null ? name : displayName);
        return body.replace("{shop}", shop).replace("{name}", first == null ? "" : first).replaceAll("[ \\t]{2,}", " ").replaceAll(" ([،,!؟?.])", "$1").trim();
    }

    private static Instant ts(Object o) {
        return o instanceof java.sql.Timestamp t ? t.toInstant() : o instanceof java.time.OffsetDateTime d ? d.toInstant() : null;
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    /** Used by tests and the admin: how many of each status a chat has. */
    public List<String> statuses(UUID conversationId) {
        List<String> out = new ArrayList<>();
        for (var r : jdbc.sql("SELECT step_position, status FROM chat.auto_followups WHERE conversation_id = :c ORDER BY anchor_customer_at, step_position").param("c", conversationId).query().listOfRows())
            out.add(r.get("step_position") + ":" + r.get("status"));
        return out;
    }
}
