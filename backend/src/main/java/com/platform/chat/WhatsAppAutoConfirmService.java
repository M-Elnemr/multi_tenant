package com.platform.chat;

import com.platform.audit.AuditService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asks customers who agreed to WhatsApp contact to confirm their order, when they have not done it themselves. The first message goes out after
 * {@code confirm_after_hours} (22 by default), a reminder {@code reminder_after_hours} after that (24), each as an approved WhatsApp template with a Confirm
 * button and each costing the shop the message price from its prepaid credit. When the customer's 24-hour window is open the follow-up is a normal text and
 * costs nothing. One confirmation and one reminder per order, ever.
 */
@Service
public class WhatsAppAutoConfirmService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppAutoConfirmService.class);
    public static final String CONFIRM_TEMPLATE = "order_confirm_ar";
    public static final String REMINDER_TEMPLATE = "order_reminder_ar";
    private static final int MAX_ATTEMPTS = 3;

    private final EvolutionService evolution;
    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final MetaChannelService channels;
    private final WhatsAppConfirmService confirm;
    private final WalletService wallet;
    private final AuditService audit;
    private final long priceMinor;

    public WhatsAppAutoConfirmService(JdbcClient jdbc, MetaGraph graph, MetaChannelService channels, WhatsAppConfirmService confirm, WalletService wallet, AuditService audit,
                                      @Value("${app.whatsapp.message-price-minor:100}") long priceMinor, EvolutionService evolution) {
        this.evolution = evolution;
        this.jdbc = jdbc;
        this.graph = graph;
        this.channels = channels;
        this.confirm = confirm;
        this.wallet = wallet;
        this.audit = audit;
        this.priceMinor = priceMinor;
    }

    public long priceMinor() { return priceMinor; }

    // ---- settings --------------------------------------------------------------------------------------------

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT auto_confirm_enabled, confirm_after_hours, reminder_enabled, reminder_after_hours, brand_name, send_mode FROM chat.wa_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r.map(x -> x.get("auto_confirm_enabled")).orElse(false));
        m.put("confirmAfterHours", r.map(x -> x.get("confirm_after_hours")).orElse(22));
        m.put("reminderEnabled", r.map(x -> x.get("reminder_enabled")).orElse(true));
        m.put("reminderAfterHours", r.map(x -> x.get("reminder_after_hours")).orElse(24));
        m.put("brandName", r.map(x -> x.get("brand_name")).orElse(null));
        m.put("sendMode", r.map(x -> x.get("send_mode")).orElse("PAID"));
        return m;
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Integer confirmAfter, Boolean reminderEnabled, Integer reminderAfter, String brandName) {
        return saveSettings(tenantId, actor, enabled, confirmAfter, reminderEnabled, reminderAfter, brandName, null);
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Integer confirmAfter, Boolean reminderEnabled, Integer reminderAfter, String brandName, String sendMode) {
        if (sendMode != null && !List.of("PAID", "FREE").contains(sendMode)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown way of sending");
        if ("FREE".equals(sendMode) && !evolution.connected(tenantId)) throw BusinessException.conflict("EVOLUTION_OFF", "Link your own WhatsApp number first");
        if (confirmAfter != null && (confirmAfter < 1 || confirmAfter > 72)) throw BusinessException.badRequest("VALIDATION_ERROR", "The delay must be between 1 and 72 hours");
        if (reminderAfter != null && (reminderAfter < 1 || reminderAfter > 72)) throw BusinessException.badRequest("VALIDATION_ERROR", "The reminder delay must be between 1 and 72 hours");
        if (brandName != null && brandName.trim().length() > 40) throw BusinessException.badRequest("VALIDATION_ERROR", "The shop name is too long (40 letters at most)");
        boolean wasOn = Boolean.TRUE.equals(settings(tenantId).get("enabled"));
        jdbc.sql("""
                INSERT INTO chat.wa_settings (tenant_id, auto_confirm_enabled, enabled_at, confirm_after_hours, reminder_enabled, reminder_after_hours, brand_name, send_mode)
                VALUES (:t, coalesce(:e, FALSE), CASE WHEN coalesce(:e, FALSE) THEN now() END, coalesce(:c, 22), coalesce(:re, TRUE), coalesce(:r, 24), :b, coalesce(:sm, 'PAID'))
                ON CONFLICT (tenant_id) DO UPDATE SET
                  auto_confirm_enabled = coalesce(:e, chat.wa_settings.auto_confirm_enabled),
                  enabled_at = CASE WHEN :e IS TRUE AND NOT chat.wa_settings.auto_confirm_enabled THEN now() ELSE chat.wa_settings.enabled_at END,
                  confirm_after_hours = coalesce(:c, chat.wa_settings.confirm_after_hours), reminder_enabled = coalesce(:re, chat.wa_settings.reminder_enabled),
                  reminder_after_hours = coalesce(:r, chat.wa_settings.reminder_after_hours), send_mode = coalesce(:sm, chat.wa_settings.send_mode), brand_name = CASE WHEN :setb THEN :b ELSE chat.wa_settings.brand_name END, updated_at = now()
                """).param("t", tenantId).param("e", enabled).param("c", confirmAfter).param("re", reminderEnabled).param("r", reminderAfter)
                .param("b", brandName == null || brandName.isBlank() ? null : brandName.trim()).param("setb", brandName != null).param("sm", sendMode).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "whatsapp_auto_confirm", null, null);
        return settings(tenantId);
    }

    // ---- templates -------------------------------------------------------------------------------------------

    private static Map<String, Object> templateBody(String name) {
        boolean confirmTpl = CONFIRM_TEMPLATE.equals(name);
        String text = confirmTpl
                ? "أهلاً {{1}}، طلبك رقم {{2}} من {{3}} وصلنا. اضغط تأكيد الطلب لنؤكده ونتواصل معك للتسليم."
                : "تذكير: طلبك رقم {{1}} من {{2}} في انتظار تأكيدك. اضغط تأكيد الطلب لإتمامه.";
        List<String> example = confirmTpl ? List.of("أحمد", "ST-20260101-000001", "متجر النور") : List.of("ST-20260101-000001", "متجر النور");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("category", "UTILITY");
        m.put("language", "ar");
        m.put("components", List.of(
                Map.of("type", "BODY", "text", text, "example", Map.of("body_text", List.of(example))),
                Map.of("type", "BUTTONS", "buttons", List.of(Map.of("type", "QUICK_REPLY", "text", "تأكيد الطلب")))));
        return m;
    }

    /** Sends both templates to Meta for approval (safe to repeat: Meta answers that a template already exists). */
    public List<Map<String, Object>> registerTemplates(UUID tenantId) {
        var ch = channels.whatsappChannel(tenantId);
        if (ch == null) throw BusinessException.badRequest("NO_WHATSAPP", "Connect your WhatsApp number first");
        if (ch.wabaId() == null) throw BusinessException.badRequest("NO_WABA", "Add your WhatsApp Business Account ID in the channel details first");
        for (String name : List.of(CONFIRM_TEMPLATE, REMINDER_TEMPLATE)) {
            try {
                graph.post(ch.wabaId() + "/message_templates", ch.token(), templateBody(name));
                setTemplate(tenantId, name, "PENDING", null);
            } catch (MetaGraph.MetaException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("already")) continue;   // already registered: the status refresh below tells the truth
                setTemplate(tenantId, name, "REJECTED", e.getMessage());
            }
        }
        return refreshTemplates(tenantId);
    }

    /** Asks Meta for the approval status of both templates. */
    public List<Map<String, Object>> refreshTemplates(UUID tenantId) {
        var ch = channels.whatsappChannel(tenantId);
        if (ch != null && ch.wabaId() != null) {
            for (String name : List.of(CONFIRM_TEMPLATE, REMINDER_TEMPLATE)) {
                try {
                    Map<String, Object> res = graph.get(ch.wabaId() + "/message_templates?name=" + name + "&fields=name,status,rejected_reason", ch.token());
                    if (res.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> t)
                        setTemplate(tenantId, name, String.valueOf(t.get("status")), t.get("rejected_reason") == null || "NONE".equals(t.get("rejected_reason")) ? null : String.valueOf(t.get("rejected_reason")));
                } catch (MetaGraph.MetaException e) {
                    log.debug("Template status check failed: {}", e.getMessage());
                }
            }
        }
        return templates(tenantId);
    }

    private void setTemplate(UUID tenantId, String name, String status, String reason) {
        jdbc.sql("INSERT INTO chat.wa_templates (tenant_id, name, status, rejected_reason) VALUES (:t, :n, :s, :r) ON CONFLICT (tenant_id, name) DO UPDATE SET status = :s, rejected_reason = :r, updated_at = now()")
                .param("t", tenantId).param("n", name).param("s", status).param("r", reason == null ? null : reason.substring(0, Math.min(300, reason.length()))).update();
    }

    public List<Map<String, Object>> templates(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT name, status, rejected_reason FROM chat.wa_templates WHERE tenant_id = :t ORDER BY name").param("t", tenantId).query().listOfRows());
    }

    private boolean approved(UUID tenantId, String name) {
        return "APPROVED".equals(jdbc.sql("SELECT status FROM chat.wa_templates WHERE tenant_id = :t AND name = :n").param("t", tenantId).param("n", name).query(String.class).optional().orElse(null));
    }

    /** What the owner needs to switch on: each item true when ready. */
    public Map<String, Object> readiness(UUID tenantId) {
        var ch = channels.whatsappChannel(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelConnected", ch != null);
        m.put("wabaKnown", ch != null && ch.wabaId() != null);
        m.put("templatesApproved", approved(tenantId, CONFIRM_TEMPLATE) && approved(tenantId, REMINDER_TEMPLATE));
        m.put("hasCredit", wallet.balance(tenantId) >= priceMinor);
        return m;
    }

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
                       s.confirm_after_hours, s.reminder_enabled, s.reminder_after_hours, s.brand_name, s.send_mode,
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
                    boolean retryable = cstatus == null || "WAITING_CREDIT".equals(cstatus) || ("FAILED".equals(cstatus) && ((Number) row.get("cattempts")).intValue() < MAX_ATTEMPTS);
                    if (retryable && !now.isBefore(created.plus(Duration.ofHours(((Number) row.get("confirm_after_hours")).longValue()))) && attempt("CONFIRM", row, now)) sent++;
                } else if (Boolean.TRUE.equals(row.get("reminder_enabled"))) {
                    String rstatus = (String) row.get("rstatus");
                    boolean retryable = rstatus == null || "WAITING_CREDIT".equals(rstatus) || ("FAILED".equals(rstatus) && ((Number) row.get("rattempts")).intValue() < MAX_ATTEMPTS);
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
        if ("FREE".equals(row.get("send_mode"))) {   // the shop chose its own linked number: a normal free message, no template and no charge; nothing is ever sent through the paid route instead
            if (!evolution.connected(tenantId)) return false;
            String text = confirm.askText(tenantId, to, orderNo).orElse(null);
            if (text == null) return false;
            boolean ok = evolution.sendFree(tenantId, to, text);
            record(tenantId, orderId, kind, ok ? "FREE_TEXT" : "FAILED", 0, null, ok ? null : "Unofficial WhatsApp send failed", now, ok);
            return ok;
        }
        var ch = channels.whatsappChannel(tenantId);
        if (ch == null) return false;
        String brand = row.get("brand_name") != null ? (String) row.get("brand_name")
                : jdbc.sql("SELECT coalesce(nullif(store_name, ''), '') FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElse("");
        if (brand == null || brand.isBlank()) brand = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
        if (brand.length() > 40) brand = brand.substring(0, 40);

        boolean windowOpen = jdbc.sql("SELECT count(*) FROM chat.wa_windows WHERE tenant_id = :t AND number = :n AND last_inbound_at > :since")
                .param("t", tenantId).param("n", to).param("since", Timestamp.from(now.minus(Duration.ofHours(24)))).query(Long.class).single() > 0;
        if (windowOpen) {   // they wrote to us in the last 24 hours: a normal message is allowed and free
            String text = confirm.askText(tenantId, to, orderNo).orElse(null);
            if (text == null) return false;
            try {
                graph.post(ch.phoneId() + "/messages", ch.token(), Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text)));
                record(tenantId, orderId, kind, "FREE_TEXT", 0, null, null, now, true);
                return true;
            } catch (MetaGraph.MetaException e) {
                record(tenantId, orderId, kind, "FAILED", 0, null, e.getMessage(), now, false);
                return false;
            }
        }

        String template = "CONFIRM".equals(kind) ? CONFIRM_TEMPLATE : REMINDER_TEMPLATE;
        if (!approved(tenantId, template)) return false;   // not approved by Meta yet: nothing is sent and nothing is charged
        String ref = "wa-" + kind.toLowerCase() + "-" + orderId;
        if (!wallet.tryDebit(tenantId, priceMinor, ref, "WhatsApp " + kind.toLowerCase() + " " + orderNo)) {
            boolean first = record(tenantId, orderId, kind, "WAITING_CREDIT", 0, null, null, now, false);
            if (first) wallet.notifyOwner(tenantId, "رصيد واتساب غير كافٍ", "لم نستطع إرسال رسالة تأكيد الطلب " + orderNo + ". اشحن الرصيد ليعمل التأكيد التلقائي.", "WALLET_LOW");
            return false;
        }
        List<Map<String, Object>> body = "CONFIRM".equals(kind)
                ? List.of(param(firstName((String) row.get("customer_name_snapshot"))), param(orderNo), param(brand)) : List.of(param(orderNo), param(brand));
        Map<String, Object> msg = Map.of("messaging_product", "whatsapp", "to", to, "type", "template", "template", Map.of("name", template, "language", Map.of("code", "ar"),
                "components", List.of(Map.of("type", "body", "parameters", body), Map.of("type", "button", "sub_type", "quick_reply", "index", "0", "parameters", List.of(Map.of("type", "payload", "payload", "CONFIRM:" + orderNo))))));
        try {
            Map<String, Object> res = graph.post(ch.phoneId() + "/messages", ch.token(), msg);
            String wamid = res.get("messages") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> mm && mm.get("id") != null ? String.valueOf(mm.get("id")) : null;
            record(tenantId, orderId, kind, "SENT", (int) priceMinor, wamid, null, now, true);
            return true;
        } catch (MetaGraph.MetaException e) {
            wallet.credit(tenantId, priceMinor, "REFUND", ref, "Message not delivered to Meta", null);   // never charge for a message that was not sent
            record(tenantId, orderId, kind, "FAILED", 0, null, e.getMessage(), now, false);
            return false;
        }
    }

    private static Map<String, Object> param(String text) { return Map.of("type", "text", "text", text == null || text.isBlank() ? "-" : text); }

    private static String firstName(String full) { return full == null ? "" : full.trim().split("\\s+")[0]; }

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
