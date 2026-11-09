package com.platform.chat;

import com.platform.audit.AuditService;
import com.platform.billing.OutboundBilling;
import com.platform.billing.WalletService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asks customers who agreed to WhatsApp contact to confirm their order, when they have not done it themselves. The first message goes out after
 * {@code confirm_after_hours} (22 by default), a reminder {@code reminder_after_hours} after that (24). Both go ONLY from the shop's own linked WhatsApp number and each
 * costs 0.1 EGP, taken before sending and given back when the send fails. No credit: nothing is sent, the row says SKIPPED_NO_CREDIT and the owner is told (at most twice a day).
 * No linked own number: the row says NO_OWN_NUMBER. One confirmation and one reminder per order, ever. The official WhatsApp (Meta Cloud API) is never used for sending.
 */
@Service
public class WhatsAppAutoConfirmService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppAutoConfirmService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final java.util.Set<String> RETRY = java.util.Set.of("WAITING_CREDIT", "SKIPPED_NO_CREDIT", "NO_OWN_NUMBER");

    private final EvolutionService evolution;
    private final JdbcClient jdbc;
    private final WhatsAppConfirmService confirm;
    private final WalletService wallet;
    private final OutboundBilling billing;
    private final OutboundThread thread;
    private final AuditService audit;

    public WhatsAppAutoConfirmService(JdbcClient jdbc, WhatsAppConfirmService confirm, WalletService wallet, OutboundBilling billing, OutboundThread thread, AuditService audit, EvolutionService evolution) {
        this.evolution = evolution;
        this.jdbc = jdbc;
        this.confirm = confirm;
        this.wallet = wallet;
        this.billing = billing;
        this.thread = thread;
        this.audit = audit;
    }

    public long priceMinor() { return billing.priceMinor(); }

    // ---- settings --------------------------------------------------------------------------------------------

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT auto_confirm_enabled, confirm_after_hours, reminder_enabled, reminder_after_hours, brand_name, send_mode FROM chat.wa_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.map(x -> x.get("auto_confirm_enabled")).orElse(false));
        m.put("confirmAfterHours", r.map(x -> x.get("confirm_after_hours")).orElse(22));
        m.put("reminderEnabled", r.map(x -> x.get("reminder_enabled")).orElse(true));
        m.put("reminderAfterHours", r.map(x -> x.get("reminder_after_hours")).orElse(24));
        m.put("brandName", r.map(x -> x.get("brand_name")).orElse(null));
        m.put("sendMode", "FREE");   // always the shop's own number
        return m;
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Integer confirmAfter, Boolean reminderEnabled, Integer reminderAfter, String brandName) {
        return saveSettings(tenantId, actor, enabled, confirmAfter, reminderEnabled, reminderAfter, brandName, null);
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Integer confirmAfter, Boolean reminderEnabled, Integer reminderAfter, String brandName, String sendMode) {
        // sendMode is accepted for older screens and ignored: every message goes from the shop's own number
        if (confirmAfter != null && (confirmAfter < 1 || confirmAfter > 72)) throw BusinessException.badRequest("VALIDATION_ERROR", "The delay must be between 1 and 72 hours");
        if (reminderAfter != null && (reminderAfter < 1 || reminderAfter > 72)) throw BusinessException.badRequest("VALIDATION_ERROR", "The reminder delay must be between 1 and 72 hours");
        if (brandName != null && brandName.trim().length() > 40) throw BusinessException.badRequest("VALIDATION_ERROR", "The shop name is too long (40 letters at most)");
        boolean wasOn = Boolean.TRUE.equals(settings(tenantId).get("enabled"));
        jdbc.sql("""
                INSERT INTO chat.wa_settings (tenant_id, auto_confirm_enabled, enabled_at, confirm_after_hours, reminder_enabled, reminder_after_hours, brand_name, send_mode)
                VALUES (:t, coalesce(:e, FALSE), CASE WHEN coalesce(:e, FALSE) THEN now() END, coalesce(:c, 22), coalesce(:re, TRUE), coalesce(:r, 24), :b, 'FREE')
                ON CONFLICT (tenant_id) DO UPDATE SET
                  auto_confirm_enabled = coalesce(:e, chat.wa_settings.auto_confirm_enabled),
                  enabled_at = CASE WHEN :e IS TRUE AND NOT chat.wa_settings.auto_confirm_enabled THEN now() ELSE chat.wa_settings.enabled_at END,
                  confirm_after_hours = coalesce(:c, chat.wa_settings.confirm_after_hours), reminder_enabled = coalesce(:re, chat.wa_settings.reminder_enabled),
                  reminder_after_hours = coalesce(:r, chat.wa_settings.reminder_after_hours), send_mode = 'FREE', brand_name = CASE WHEN :setb THEN :b ELSE chat.wa_settings.brand_name END, updated_at = now()
                """).param("t", tenantId).param("e", enabled).param("c", confirmAfter).param("re", reminderEnabled).param("r", reminderAfter)
                .param("b", brandName == null || brandName.isBlank() ? null : brandName.trim()).param("setb", brandName != null).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "whatsapp_auto_confirm", null, null);
        return settings(tenantId);
    }

    /** What the owner needs to switch on: each item true when ready. */
    public Map<String, Object> readiness(UUID tenantId) {
        boolean own = evolution.connected(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownNumberConnected", own);
        m.put("channelConnected", own);
        m.put("hasCredit", billing.affordable(tenantId, 1));
        return m;
    }

    /** Kept so older screens that read it keep working: there are no templates any more. */
    public List<Map<String, Object>> templates(UUID tenantId) { return List.of(); }

    public List<Map<String, Object>> messages(UUID tenantId) {
        return Rows.camel(jdbc.sql("""
                SELECT w.kind, w.status, w.cost_minor, w.error, w.sent_at, w.created_at, o.order_number FROM chat.wa_outbound w JOIN commerce.orders o ON o.id = w.order_id
                WHERE w.tenant_id = :t ORDER BY w.created_at DESC LIMIT 50
                """).param("t", tenantId).query().listOfRows());
    }

    // ---- the job ---------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.whatsapp.auto-confirm-interval-ms:300000}", initialDelayString = "${app.whatsapp.auto-confirm-initial-delay-ms:60000}")
    public void scheduled() {
        try { runOnce(Instant.now()); } catch (RuntimeException e) { log.warn("WhatsApp auto-confirm run failed", e); }
    }

    /** One pass over every shop. Returns how many messages (template or free text) were sent. */
    public int runOnce(Instant now) { return runOnce(now, null); }

    /** One pass, optionally for a single shop (used by tests and support). */
    public synchronized int runOnce(Instant now, UUID onlyTenant) {
        var rows = jdbc.sql("""
                SELECT o.id, o.tenant_id, o.order_number, o.customer_name_snapshot, o.whatsapp_number, o.created_at,
                       s.confirm_after_hours, s.reminder_enabled, s.reminder_after_hours, s.brand_name,
                       c.status AS cstatus, c.attempts AS cattempts, c.sent_at AS csent,
                       r.status AS rstatus, r.attempts AS rattempts
                FROM commerce.orders o
                JOIN chat.wa_settings s ON s.tenant_id = o.tenant_id AND s.auto_confirm_enabled
                LEFT JOIN chat.wa_outbound c ON c.order_id = o.id AND c.kind = 'CONFIRM'
                LEFT JOIN chat.wa_outbound r ON r.order_id = o.id AND r.kind = 'REMINDER'
                WHERE o.whatsapp_number IS NOT NULL AND o.whatsapp_opt_in_at IS NOT NULL AND o.confirmed_at IS NULL AND o.whatsapp_confirmed_at IS NULL
                  AND o.status IN ('REQUESTED', 'PREPARING') AND s.enabled_at IS NOT NULL AND o.created_at >= s.enabled_at AND o.created_at > :cutoff
                  AND (CAST(:only AS uuid) IS NULL OR o.tenant_id = CAST(:only AS uuid))
                  AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = o.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))
                ORDER BY o.created_at LIMIT 200
                """).param("cutoff", Timestamp.from(now.minus(Duration.ofDays(7)))).param("only", onlyTenant).query().listOfRows();
        int sent = 0;
        for (var row : rows) {
            try {
                Instant created = ((Timestamp) row.get("created_at")).toInstant();
                String cstatus = (String) row.get("cstatus");
                boolean firstDone = "SENT".equals(cstatus) || "FREE_TEXT".equals(cstatus);
                if (!firstDone) {
                    boolean retryable = cstatus == null || RETRY.contains(cstatus) || ("FAILED".equals(cstatus) && ((Number) row.get("cattempts")).intValue() < MAX_ATTEMPTS);
                    if (retryable && !now.isBefore(created.plus(Duration.ofHours(((Number) row.get("confirm_after_hours")).longValue()))) && attempt("CONFIRM", row, now)) sent++;
                } else if (Boolean.TRUE.equals(row.get("reminder_enabled"))) {
                    String rstatus = (String) row.get("rstatus");
                    boolean retryable = rstatus == null || RETRY.contains(rstatus) || ("FAILED".equals(rstatus) && ((Number) row.get("rattempts")).intValue() < MAX_ATTEMPTS);
                    Instant first = ((Timestamp) row.get("csent")).toInstant();
                    if (retryable && !now.isBefore(first.plus(Duration.ofHours(((Number) row.get("reminder_after_hours")).longValue()))) && attempt("REMINDER", row, now)) sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Auto-confirm failed for order {}: {}", row.get("order_number"), e.toString());
            }
        }
        return sent;
    }

    private boolean attempt(String kind, Map<String, Object> row, Instant now) {
        UUID tenantId = (UUID) row.get("tenant_id"), orderId = (UUID) row.get("id");
        String orderNo = (String) row.get("order_number"), to = ((String) row.get("whatsapp_number")).replaceAll("\\D", "");
        if (!evolution.connected(tenantId)) {   // no own number linked: nothing is sent, the row says why
            record(tenantId, orderId, kind, "NO_OWN_NUMBER", 0, null, "No own WhatsApp number is linked", now, false);
            return false;
        }
        String text = confirm.askText(tenantId, to, orderNo).orElse(null);
        if (text == null) return false;
        String ref = "wa-" + kind.toLowerCase() + "-" + orderId;
        if (!billing.charge(tenantId, ref, "WhatsApp " + kind.toLowerCase() + " " + orderNo)) {   // the 0.1 EGP is taken before sending; no credit, no message
            record(tenantId, orderId, kind, "SKIPPED_NO_CREDIT", 0, null, null, now, false);
            billing.notifyNoCredit(tenantId, "wa-confirm", "رصيد واتساب غير كافٍ", "لم نستطع إرسال رسالة تأكيد الطلب " + orderNo + ". اشحن الرصيد ليعمل التأكيد التلقائي.");
            return false;
        }
        boolean ok;
        try { ok = evolution.sendFree(tenantId, to, text); } catch (RuntimeException e) { ok = false; }
        if (!ok) {
            billing.refund(tenantId, ref, "Message not sent");   // never charge for a message that was not sent
            record(tenantId, orderId, kind, "FAILED", 0, null, "Own number send failed", now, false);
            return false;
        }
        record(tenantId, orderId, kind, "SENT", (int) billing.priceMinor(), null, null, now, true);
        thread.record(tenantId, "WHATSAPP", to, "CONFIRM", text);
        return true;
    }

    /** Writes or updates the outbound row; true when the row is new (used to notify the owner only once). */
    private boolean record(UUID tenantId, UUID orderId, String kind, String status, int cost, String wamid, String error, Instant now, boolean sentNow) {
        return jdbc.sql("""
                INSERT INTO chat.wa_outbound (tenant_id, order_id, kind, status, attempts, cost_minor, wamid, error, sent_at) VALUES (:t, :o, :k, :s, :a, :c, :w, :e, :sa)
                ON CONFLICT (order_id, kind) DO UPDATE SET status = :s, attempts = chat.wa_outbound.attempts + :a, cost_minor = :c, wamid = coalesce(:w, chat.wa_outbound.wamid), error = :e,
                  sent_at = coalesce(:sa, chat.wa_outbound.sent_at)
                RETURNING (xmax = 0)
                """).param("t", tenantId).param("o", orderId).param("k", kind).param("s", status).param("a", "FAILED".equals(status) ? 1 : 0).param("c", cost).param("w", wamid)
                .param("e", error == null ? null : error.substring(0, Math.min(300, error.length()))).param("sa", sentNow ? Timestamp.from(now) : null).query(Boolean.class).single();
    }

}
