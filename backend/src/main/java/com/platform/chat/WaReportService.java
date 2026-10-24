package com.platform.chat;

import com.platform.copilot.CopilotService;
import com.platform.shared.BusinessException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The daily business report on WhatsApp, sent from ONE platform number. A shop links itself by sending a code to that number; from then on every message it
 * sends opens a 24 hour window in which we may answer for free. The report goes out after the shop's local 23:59 as plain text with a "send me tomorrow's report"
 * button; tapping it is a new inbound message, so the window reopens and the chain continues without any paid template. If the window is closed we do not pay for a
 * template: the day is marked and the dashboard asks the shop to message the number again.
 */
@Service
public class WaReportService {
    private static final Logger log = LoggerFactory.getLogger(WaReportService.class);
    static final String BUTTON_ID = "REPORT_TOMORROW";
    private static final Pattern CODE = Pattern.compile("MNS\\s*[- ]?\\s*(\\d{6})", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EvolutionService evolution;
    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final CopilotService copilot;
    private final String phoneId;
    private final String token;
    private final String displayNumber;

    public WaReportService(JdbcClient jdbc, MetaGraph graph, CopilotService copilot, @Value("${app.whatsapp.platform.phone-id:}") String phoneId,
                           @Value("${app.whatsapp.platform.token:}") String token, @Value("${app.whatsapp.platform.number:}") String displayNumber, EvolutionService evolution) {
        this.evolution = evolution;
        this.jdbc = jdbc;
        this.graph = graph;
        this.copilot = copilot;
        this.phoneId = phoneId == null || phoneId.isBlank() ? null : phoneId.trim();
        this.token = token == null || token.isBlank() ? null : token.trim();
        this.displayNumber = displayNumber == null ? "" : displayNumber.trim();
    }

    public boolean configured() { return phoneId != null && token != null; }

    /** True when a webhook entry belongs to our platform number, so it is never mistaken for a shop's own channel. */
    public boolean isPlatformNumber(String phoneNumberId) { return phoneId != null && phoneId.equals(phoneNumberId); }

    // ---- shop side ---------------------------------------------------------------------------------------------------------

    public Map<String, Object> status(UUID t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("platformNumber", displayNumber);
        var c = jdbc.sql("SELECT phone, enabled, send_after, language, last_inbound_at, needs_ping FROM whatsapp.contacts WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst().orElse(null);
        m.put("linked", c != null);
        if (c != null) {
            Instant last = ((Timestamp) c.get("last_inbound_at")).toInstant();
            m.put("phone", c.get("phone"));
            m.put("enabled", c.get("enabled"));
            m.put("sendAfter", String.valueOf(c.get("send_after")).substring(0, 5));
            m.put("language", c.get("language"));
            m.put("windowOpen", windowOpen(last));
            m.put("windowClosesAt", last.plus(Duration.ofHours(24)).toString());
            m.put("needsPing", c.get("needs_ping"));
            var log = jdbc.sql("SELECT day, status FROM whatsapp.report_log WHERE tenant_id = :t ORDER BY day DESC LIMIT 1").param("t", t).query().listOfRows().stream().findFirst().orElse(null);
            if (log != null) { m.put("lastDay", String.valueOf(log.get("day"))); m.put("lastStatus", log.get("status")); }
        }
        return m;
    }

    private static boolean windowOpen(Instant lastInbound) { return windowOpen(lastInbound, Instant.now()); }

    private static boolean windowOpen(Instant lastInbound, Instant now) { return lastInbound.isAfter(now.minus(Duration.ofHours(24)).plus(Duration.ofMinutes(5))); }

    /** A one-time code the shop sends to our number. Older unused codes stop working. */
    public Map<String, Object> newCode(UUID t, UUID user) {
        if (!configured()) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "WHATSAPP_NOT_READY", "The WhatsApp report number is not set up yet");
        jdbc.sql("DELETE FROM whatsapp.link_codes WHERE tenant_id = :t AND used_at IS NULL").param("t", t).update();
        String code = "MNS" + String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.sql("INSERT INTO whatsapp.link_codes (code, tenant_id, created_by, expires_at) VALUES (:c, :t, :u, :e)").param("c", code).param("t", t).param("u", user).param("e", Timestamp.from(Instant.now().plus(Duration.ofMinutes(30)))).update();
        return Map.of("code", code, "platformNumber", displayNumber, "expiresInMinutes", 30);
    }

    public Map<String, Object> update(UUID t, Boolean enabled, String sendAfter, String language) {
        if (language != null && !List.of("ar", "en").contains(language)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown language");
        LocalTime at = null;
        if (sendAfter != null) {
            try { at = LocalTime.parse(sendAfter); } catch (RuntimeException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Time must look like 23:59"); }
            if (at.isBefore(LocalTime.of(18, 0))) throw BusinessException.badRequest("VALIDATION_ERROR", "The report is an end-of-day report: choose a time from 18:00 to 23:59");
        }
        if (jdbc.sql("UPDATE whatsapp.contacts SET enabled = coalesce(:e, enabled), send_after = coalesce(CAST(:a AS time), send_after), language = coalesce(:l, language) WHERE tenant_id = :t")
                .param("e", enabled).param("a", at == null ? null : at.toString()).param("l", language).param("t", t).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Link your WhatsApp number first");
        return status(t);
    }

    public void unlink(UUID t) { jdbc.sql("DELETE FROM whatsapp.contacts WHERE tenant_id = :t").param("t", t).update(); }

    // ---- inbound (called by the webhook for our platform number) --------------------------------------------------------------

    public void inbound(String from, String text) {
        String phone = from == null ? "" : from.replaceAll("\\D", "");
        if (phone.isEmpty()) return;
        String body = text == null ? "" : text.trim();
        var existing = jdbc.sql("SELECT tenant_id, language FROM whatsapp.contacts WHERE phone = :p").param("p", phone).query().listOfRows().stream().findFirst().orElse(null);
        Matcher m = CODE.matcher(body);
        if (m.find()) {
            link(phone, "MNS" + m.group(1));
            return;
        }
        if (existing == null) {
            sendText(phone, "أهلًا 👋 عشان أبعتلك تقرير نشاطك كل يوم، ادخل على لوحة التحكم > الإعدادات > تقرير واتساب واطلب كود الربط وابعته هنا.\nTo get your daily report, open Dashboard > Settings > WhatsApp report, get a link code and send it here.");
            return;
        }
        UUID t = (UUID) existing.get("tenant_id");
        boolean ar = !"en".equals(existing.get("language"));
        jdbc.sql("UPDATE whatsapp.contacts SET last_inbound_at = now(), needs_ping = FALSE WHERE tenant_id = :t").param("t", t).update();
        if (BUTTON_ID.equals(body) || body.contains("تقرير بكرة") || body.toLowerCase().contains("tomorrow")) {
            sendText(phone, ar ? "تمام ✅ هيوصلك تقرير النهارده بعد آخر اليوم." : "Done ✅ you will get today's report at the end of the day.");
        } else if (body.contains("تقرير") || body.toLowerCase().contains("report")) {
            sendReportNow(t);
        } else if (body.contains("نصيحة") || body.contains("اعمل") || body.toLowerCase().contains("advice")) {
            sendText(phone, copilot.dailyActionsText(t, ar ? "ar" : "en"));
        } else {
            sendText(phone, ar ? "أهلًا! ابعت \"تقرير\" لتقرير النهارده دلوقتي، أو \"نصيحة\" لأهم ٣ حاجات تعملها. التقرير اليومي بيوصلك تلقائي كل ليلة." : "Hi! Send \"report\" for today's report now, or \"advice\" for the 3 best things to do. The daily report arrives every night.");
        }
    }

    private void link(String phone, String code) {
        var row = jdbc.sql("SELECT tenant_id, created_by FROM whatsapp.link_codes WHERE code = :c AND used_at IS NULL AND expires_at > now()").param("c", code).query().listOfRows().stream().findFirst().orElse(null);
        if (row == null) {
            sendText(phone, "الكود ده غير صحيح أو انتهت صلاحيته. اطلب كود جديد من لوحة التحكم. / That code is wrong or expired. Get a new one in the dashboard.");
            return;
        }
        UUID t = (UUID) row.get("tenant_id");
        // One number belongs to one shop: linking it again moves it.
        jdbc.sql("DELETE FROM whatsapp.contacts WHERE phone = :p AND tenant_id <> :t").param("p", phone).param("t", t).update();
        jdbc.sql("""
                INSERT INTO whatsapp.contacts (tenant_id, phone, last_inbound_at, linked_by) VALUES (:t, :p, now(), :u)
                ON CONFLICT (tenant_id) DO UPDATE SET phone = :p, last_inbound_at = now(), needs_ping = FALSE, linked_by = :u, linked_at = now()
                """).param("t", t).param("p", phone).param("u", row.get("created_by")).update();
        jdbc.sql("UPDATE whatsapp.link_codes SET used_at = now() WHERE code = :c").param("c", code).update();
        sendText(phone, "تم الربط ✅ هيوصلك تقرير نشاطك كل يوم بعد ١١:٥٩ بالليل، وفيه ٣ حاجات تعملها تاني يوم. ابعت \"تقرير\" في أي وقت لتقرير دلوقتي.\nLinked ✅ you will get your daily report every night, with 3 actions for tomorrow.");
    }

    // ---- composing and sending -----------------------------------------------------------------------------------------------

    /** The day's numbers. {@code day} is the shop's local date; the money is in the shop currency. */
    public String compose(UUID t, LocalDate day, String lang) {
        boolean ar = !"en".equalsIgnoreCase(lang);
        ZoneId tz = zone(t);
        var tenant = jdbc.sql("SELECT name, default_currency FROM core.tenants WHERE id = :t").param("t", t).query().singleRow();
        var d = jdbc.sql("""
                SELECT count(*) AS orders, coalesce(sum(total_minor), 0) AS sales FROM commerce.orders o
                WHERE o.tenant_id = :t AND o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED') AND (o.created_at AT TIME ZONE :tz)::date = :d
                """).param("t", t).param("tz", tz.getId()).param("d", java.sql.Date.valueOf(day)).query().singleRow();
        var top = jdbc.sql("""
                SELECT i.product_name_snapshot AS name, sum(i.quantity) AS units FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id
                WHERE o.tenant_id = :t AND o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED') AND (o.created_at AT TIME ZONE :tz)::date = :d GROUP BY i.product_name_snapshot ORDER BY units DESC LIMIT 1
                """).param("t", t).param("tz", tz.getId()).param("d", java.sql.Date.valueOf(day)).query().listOfRows().stream().findFirst().orElse(null);
        long low = jdbc.sql("SELECT count(*) FROM commerce.inventory_items WHERE tenant_id = :t AND quantity_on_hand - quantity_reserved <= low_stock_threshold").param("t", t).query(Long.class).single();
        long orders = ((Number) d.get("orders")).longValue();
        String cur = String.valueOf(tenant.get("default_currency"));
        String sales = fmt(((Number) d.get("sales")).longValue());
        StringBuilder b = new StringBuilder();
        if (ar) {
            b.append("📊 تقرير ").append(tenant.get("name")).append(" - ").append(day).append("\n");
            b.append("• الأوردرات: ").append(orders).append("\n• المبيعات: ").append(sales).append(" ").append(cur).append("\n");
            if (top != null) b.append("• الأكثر مبيعًا: ").append(top.get("name")).append(" (").append(top.get("units")).append(")\n");
            if (low > 0) b.append("• ⚠️ منتجات مخزونها قليل: ").append(low).append("\n");
            b.append("\n🎯 ٣ حاجات تعملها بكرة:\n");
        } else {
            b.append("📊 ").append(tenant.get("name")).append(" daily report - ").append(day).append("\n");
            b.append("• Orders: ").append(orders).append("\n• Sales: ").append(sales).append(" ").append(cur).append("\n");
            if (top != null) b.append("• Best seller: ").append(top.get("name")).append(" (").append(top.get("units")).append(")\n");
            if (low > 0) b.append("• ⚠️ Low-stock products: ").append(low).append("\n");
            b.append("\n🎯 3 things to do tomorrow:\n");
        }
        b.append(copilot.dailyActionsText(t, lang));
        String out = b.toString();
        return out.length() > 1000 ? out.substring(0, 997) + "..." : out;
    }

    private static String fmt(long minor) {
        double v = minor / 100.0;
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private ZoneId zone(UUID t) {
        try { return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single()); } catch (RuntimeException e) { return ZoneId.of("Africa/Cairo"); }
    }

    void sendText(String to, String body) {
        graph.post(phoneId + "/messages", token, Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", body)));
    }

    private Map<String, Object> sendWithButton(String to, String body, boolean ar) {
        return graph.post(phoneId + "/messages", token, Map.of("messaging_product", "whatsapp", "to", to, "type", "interactive", "interactive", Map.of("type", "button", "body", Map.of("text", body),
                "action", Map.of("buttons", List.of(Map.of("type", "reply", "reply", Map.of("id", BUTTON_ID, "title", ar ? "ابعت لي تقرير بكرة" : "Send me tomorrow's report")))))));
    }

    /** "Send now": today's report to the linked number, if its window is open. Returns what happened. */
    public Map<String, Object> sendReportNow(UUID t) {
        var c = jdbc.sql("SELECT phone, language, last_inbound_at FROM whatsapp.contacts WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Link your WhatsApp number first"));
        if (!configured()) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "WHATSAPP_NOT_READY", "The WhatsApp report number is not set up yet");
        if (!windowOpen(((Timestamp) c.get("last_inbound_at")).toInstant()))
            throw BusinessException.conflict("WINDOW_CLOSED", "Send any message to our WhatsApp number first, then try again");
        boolean ar = !"en".equals(c.get("language"));
        sendWithButton((String) c.get("phone"), compose(t, LocalDate.now(zone(t)), ar ? "ar" : "en"), ar);
        return Map.of("sent", true);
    }

    // ---- the nightly job ---------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void sendDueReports() { runDue(Instant.now()); }

    /** Sends every report that is due at {@code now}: not yet sent for its day, after the shop's chosen time (or up to 03:00 the next morning to catch up). */
    public int runDue(Instant now) {
        int sent = runFree(now);
        if (!configured()) return sent;
        for (var c : jdbc.sql("SELECT c.tenant_id, c.phone, c.send_after, c.language, c.last_inbound_at FROM whatsapp.contacts c WHERE c.enabled AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = c.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) AND NOT EXISTS (SELECT 1 FROM chat.evolution_settings e WHERE e.tenant_id = c.tenant_id AND e.enabled AND e.report_mode = 'FREE' AND e.report_enabled)").query().listOfRows()) {
            UUID t = (UUID) c.get("tenant_id");
            try {
                ZonedDateTime local = now.atZone(zone(t));
                LocalTime sendAfter = ((java.sql.Time) c.get("send_after")).toLocalTime();
                LocalDate day = local.toLocalTime().isBefore(LocalTime.of(3, 0)) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
                boolean due = local.toLocalTime().isBefore(LocalTime.of(3, 0)) || !local.toLocalTime().isBefore(sendAfter);
                if (!due) continue;
                if (jdbc.sql("SELECT count(*) FROM whatsapp.report_log WHERE tenant_id = :t AND day = :d AND status = 'SENT'").param("t", t).param("d", java.sql.Date.valueOf(day)).query(Long.class).single() > 0) continue;
                boolean ar = !"en".equals(c.get("language"));
                if (!windowOpen(((Timestamp) c.get("last_inbound_at")).toInstant(), now)) {
                    record(t, day, "WINDOW_CLOSED", null, null);
                    jdbc.sql("UPDATE whatsapp.contacts SET needs_ping = TRUE WHERE tenant_id = :t").param("t", t).update();
                    continue;
                }
                Map<String, Object> res = sendWithButton((String) c.get("phone"), compose(t, day, ar ? "ar" : "en"), ar);
                String id = res.get("messages") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> mm ? String.valueOf(mm.get("id")) : null;
                record(t, day, "SENT", id, null);
                sent++;
            } catch (RuntimeException e) {
                log.warn("WhatsApp report for {} failed: {}", t, e.getMessage());
                try { record(t, LocalDate.now(zone(t)), "FAILED", null, e.getMessage()); } catch (RuntimeException ignored) { /* the next tick tries again */ }
            }
        }
        return sent;
    }

    /** The free report: from the shop's own linked number to that same number, like a message to yourself. No platform number, no window, no charge. */
    int runFree(Instant now) {
        int sent = 0;
        for (var c : jdbc.sql("SELECT tenant_id, to_char(report_send_after, 'HH24:MI') AS send_after, report_language FROM chat.evolution_settings WHERE enabled AND status = 'CONNECTED' AND report_mode = 'FREE' AND report_enabled AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED'))").query().listOfRows()) {
            UUID t = (UUID) c.get("tenant_id");
            try {
                ZonedDateTime local = now.atZone(zone(t));
                LocalTime sendAfter = LocalTime.parse((String) c.get("send_after"));
                LocalDate day = local.toLocalTime().isBefore(LocalTime.of(3, 0)) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
                boolean due = local.toLocalTime().isBefore(LocalTime.of(3, 0)) || !local.toLocalTime().isBefore(sendAfter);
                if (!due) continue;
                if (jdbc.sql("SELECT count(*) FROM whatsapp.report_log WHERE tenant_id = :t AND day = :d AND status = 'SENT'").param("t", t).param("d", java.sql.Date.valueOf(day)).query(Long.class).single() > 0) continue;
                String self = evolution.selfNumber(t);
                if (self == null) continue;
                var d = evolution.deliver(t, self, compose(t, day, "en".equals(c.get("report_language")) ? "en" : "ar"), null);
                if (d == EvolutionService.Delivery.DEFERRED) continue;
                record(t, day, d == EvolutionService.Delivery.SENT ? "SENT" : "FAILED", null, d == EvolutionService.Delivery.SENT ? null : "Send from the own number failed");
                if (d == EvolutionService.Delivery.SENT) sent++;
            } catch (RuntimeException e) {
                log.warn("Free WhatsApp report for {} failed: {}", t, e.getMessage());
            }
        }
        return sent;
    }

    /** "Send now" through the shop's own number. */
    public Map<String, Object> sendFreeReportNow(UUID t) {
        String self = evolution.selfNumber(t);
        if (self == null) throw BusinessException.conflict("EVOLUTION_OFF", "Link your own WhatsApp number first");
        var settings = evolution.reportSettings(t);
        var d = evolution.deliver(t, self, compose(t, LocalDate.now(zone(t)), "en".equals(settings.get("language")) ? "en" : "ar"), null);
        if (d != EvolutionService.Delivery.SENT) throw BusinessException.conflict("EVOLUTION_SEND_FAILED", "The message could not be sent");
        return Map.of("sent", true);
    }

    private void record(UUID t, LocalDate day, String status, String id, String error) {
        jdbc.sql("""
                INSERT INTO whatsapp.report_log (tenant_id, day, status, message_id, error) VALUES (:t, :d, :s, :m, :e)
                ON CONFLICT (tenant_id, day) DO UPDATE SET status = :s, message_id = :m, error = :e, created_at = now() WHERE whatsapp.report_log.status <> 'SENT'
                """).param("t", t).param("d", java.sql.Date.valueOf(day)).param("s", status).param("m", id).param("e", error == null ? null : error.length() > 480 ? error.substring(0, 480) : error).update();
    }
}
