package com.platform.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.billing.WalletService;
import com.platform.chat.MetaChannelService;
import com.platform.chat.MetaGraph;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
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

/**
 * Incomplete carts: the storefront sends the cart once the shopper has typed a phone number; if no order follows, the owner sees it here after 24 hours and can
 * contact the customer. A reminder is only sent by the app when the owner switched recovery on, and then only as a normal (free) message inside the customer's
 * 24 hour WhatsApp window; a paid template goes out only when the owner ALSO allowed paid messages, is approved by Meta, and is paid from the shop's own credit.
 */
@Service
public class CartRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(CartRecoveryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String TEMPLATE = "cart_reminder_ar";

    private final GenderService genders;
    private final com.platform.chat.EvolutionService evolution;
    private final JdbcClient jdbc;
    private final MetaChannelService channels;
    private final MetaGraph graph;
    private final WalletService wallet;
    private final OpenUrls urls;
    private final AuditService audit;
    private final long priceMinor;
    private final boolean scheduler;

    public CartRecoveryService(JdbcClient jdbc, MetaChannelService channels, MetaGraph graph, WalletService wallet, OpenUrls urls, AuditService audit,
                               @Value("${app.whatsapp.marketing-price-minor:300}") long priceMinor, @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler, com.platform.chat.EvolutionService evolution, GenderService genders) {
        this.genders = genders;
        this.evolution = evolution;
        this.jdbc = jdbc;
        this.channels = channels;
        this.graph = graph;
        this.wallet = wallet;
        this.urls = urls;
        this.audit = audit;
        this.priceMinor = priceMinor;
        this.scheduler = scheduler;
    }

    // ---- capture (public storefront) -----------------------------------------------------------------------------------

    public record Line(UUID variantId, int quantity) {}

    /** Called by the storefront. An empty cart closes the open one. Returns nothing the shopper could use to read other carts. */
    public void snapshot(UUID tenantId, String clientKey, UUID userId, String name, String phoneRaw, List<Line> lines) {
        if (clientKey == null || !clientKey.matches("[A-Za-z0-9_-]{8,80}")) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid cart id");
        String phone;
        try { phone = PhoneNormalizer.normalize(phoneRaw == null ? "" : phoneRaw.replace(" ", "")); } catch (BusinessException e) { return; }   // no usable phone: nothing to recover
        if (lines == null || lines.isEmpty()) {
            jdbc.sql("UPDATE commerce.abandoned_carts SET status = 'DISMISSED', updated_at = now() WHERE tenant_id = :t AND client_key = :k AND status = 'OPEN'").param("t", tenantId).param("k", clientKey).update();
            return;
        }
        if (lines.size() > 50) throw BusinessException.badRequest("CART_TOO_LARGE", "Too many items");
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (Line l : lines) { if (l.variantId() == null || l.quantity() < 1 || l.quantity() > 1000) return; wanted.merge(l.variantId(), l.quantity(), Integer::sum); }
        var rows = jdbc.sql("SELECT v.id, v.price_minor, v.currency, v.combo_key, p.name, p.slug FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.tenant_id = :t AND v.id IN (:ids) AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'")
                .param("t", tenantId).param("ids", wanted.keySet()).query().listOfRows();
        if (rows.isEmpty()) return;
        long total = 0;
        int count = 0;
        String currency = "EGP";
        List<Map<String, Object>> items = new ArrayList<>();
        for (var r : rows) {
            int q = wanted.get((UUID) r.get("id"));
            long price = ((Number) r.get("price_minor")).longValue();
            total += price * q;
            count += q;
            currency = (String) r.get("currency");
            String combo = r.get("combo_key") == null || ((String) r.get("combo_key")).isEmpty() ? null : ((String) r.get("combo_key")).replace("|", ", ").replace("=", ": ");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", r.get("id")); m.put("name", r.get("name")); m.put("slug", r.get("slug")); m.put("variant", combo); m.put("quantity", q); m.put("priceMinor", price);
            items.add(m);
        }
        String json;
        try { json = JSON.writeValueAsString(items); } catch (Exception e) { throw new IllegalStateException(e); }
        String cleanName = name == null || name.isBlank() ? null : (name.trim().length() > 150 ? name.trim().substring(0, 150) : name.trim());
        jdbc.sql("""
                INSERT INTO commerce.abandoned_carts (tenant_id, client_key, user_id, name, phone, items, item_count, total_minor, currency)
                VALUES (:t, :k, :u, :n, :p, CAST(:i AS jsonb), :c, :tot, :cur)
                ON CONFLICT (tenant_id, client_key) DO UPDATE SET
                  created_at = CASE WHEN commerce.abandoned_carts.status = 'OPEN' THEN commerce.abandoned_carts.created_at ELSE now() END,
                  contacted_at = CASE WHEN commerce.abandoned_carts.status = 'OPEN' THEN commerce.abandoned_carts.contacted_at END,
                  reminder_status = CASE WHEN commerce.abandoned_carts.status = 'OPEN' THEN commerce.abandoned_carts.reminder_status END,
                  reminder_sent_at = CASE WHEN commerce.abandoned_carts.status = 'OPEN' THEN commerce.abandoned_carts.reminder_sent_at END,
                  status = 'OPEN', name = coalesce(:n, commerce.abandoned_carts.name), phone = :p, user_id = coalesce(:u, commerce.abandoned_carts.user_id),
                  items = CAST(:i AS jsonb), item_count = :c, total_minor = :tot, currency = :cur, updated_at = now(), recovered_order_id = NULL, recovered_at = NULL
                """).param("t", tenantId).param("k", clientKey).param("u", userId).param("n", cleanName).param("p", phone).param("i", json).param("c", count).param("tot", total).param("cur", currency).update();
    }

    /** An order was placed: every open cart of that phone is recovered. */
    public void markRecovered(UUID tenantId, String phone, UUID orderId) {
        jdbc.sql("UPDATE commerce.abandoned_carts SET status = 'RECOVERED', recovered_order_id = :o, recovered_at = now(), updated_at = now() WHERE tenant_id = :t AND phone = :p AND status = 'OPEN'")
                .param("o", orderId).param("t", tenantId).param("p", phone).update();
    }

    // ---- settings ----------------------------------------------------------------------------------------------------------

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT recovery_enabled, allow_paid, remind_after_hours, coupon_code, send_mode FROM commerce.cart_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recoveryEnabled", r != null && Boolean.TRUE.equals(r.get("recovery_enabled")));
        m.put("allowPaid", r != null && Boolean.TRUE.equals(r.get("allow_paid")));
        m.put("remindAfterHours", r == null ? 24 : r.get("remind_after_hours"));
        m.put("couponCode", r == null ? null : r.get("coupon_code"));
        m.put("sendMode", r == null ? "PAID" : r.get("send_mode"));
        m.put("messagePriceMinor", priceMinor);
        return m;
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Boolean allowPaid, Integer hours, String coupon) { return saveSettings(tenantId, actor, enabled, allowPaid, hours, coupon, null); }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean enabled, Boolean allowPaid, Integer hours, String coupon, String sendMode) {
        if (sendMode != null && !List.of("PAID", "FREE").contains(sendMode)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown way of sending");
        if ("FREE".equals(sendMode) && !evolution.connected(tenantId)) throw BusinessException.conflict("EVOLUTION_OFF", "Link your own WhatsApp number first");
        var cur = settings(tenantId);
        String mode = sendMode != null ? sendMode : (String) cur.get("sendMode");
        boolean e = enabled != null ? enabled : (Boolean) cur.get("recoveryEnabled");
        boolean p = allowPaid != null ? allowPaid : (Boolean) cur.get("allowPaid");
        int h = hours != null ? hours : ((Number) cur.get("remindAfterHours")).intValue();
        if (h < 1 || h > 168) throw BusinessException.badRequest("VALIDATION_ERROR", "Reminder delay must be between 1 and 168 hours");
        String c = coupon == null ? (String) cur.get("couponCode") : coupon.trim().isEmpty() ? null : coupon.trim().toUpperCase();
        if (c != null && !c.matches("[A-Z0-9_-]{2,40}")) throw BusinessException.badRequest("VALIDATION_ERROR", "Coupon code can only use letters, digits, - and _");
        if (c != null && jdbc.sql("SELECT count(*) FROM commerce.coupons WHERE tenant_id = :t AND upper(code) = :c AND is_active").param("t", tenantId).param("c", c).query(Long.class).single() == 0)
            throw BusinessException.badRequest("COUPON_INVALID", "That coupon does not exist or is switched off");
        if (!e) p = false;   // paid messages make no sense without recovery
        jdbc.sql("INSERT INTO commerce.cart_settings (tenant_id, recovery_enabled, allow_paid, remind_after_hours, coupon_code, send_mode) VALUES (:t, :e, :p, :h, :c, :sm) ON CONFLICT (tenant_id) DO UPDATE SET recovery_enabled = :e, allow_paid = :p, remind_after_hours = :h, coupon_code = :c, send_mode = :sm, updated_at = now()")
                .param("t", tenantId).param("e", e).param("p", p).param("h", h).param("c", c).param("sm", mode).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "cart_recovery", null, null);
        return settings(tenantId);
    }

    // ---- the owner's screen ----------------------------------------------------------------------------------------------

    /** view: due (open for 24 hours or more - the default), recent (open, younger), recovered, all. */
    public Map<String, Object> list(UUID tenantId, String view) {
        String where = switch (view == null ? "due" : view) {
            case "recent" -> "status = 'OPEN' AND created_at > now() - interval '24 hours'";
            case "recovered" -> "status = 'RECOVERED'";
            case "all" -> "TRUE";
            default -> "status = 'OPEN' AND created_at <= now() - interval '24 hours'";
        };
        var rows = jdbc.sql("SELECT id, name, phone, items::text AS items, item_count, total_minor, currency, status, created_at, updated_at, contacted_at, reminder_status, reminder_sent_at, recovered_order_id, recovered_at "
                + "FROM commerce.abandoned_carts WHERE tenant_id = :t AND " + where + " ORDER BY created_at DESC LIMIT 200").param("t", tenantId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            m.put("items", Rows.jsonList(r.get("items")));
            m.put("whatsappUrl", whatsappUrl(tenantId, (String) r.get("phone"), (String) r.get("name"), null));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("carts", out);
        res.put("stats", stats(tenantId));
        res.put("settings", settings(tenantId));
        return res;
    }

    public Map<String, Object> stats(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPEN' AND created_at <= now() - interval '24 hours') AS due_count,
                       coalesce(sum(total_minor) FILTER (WHERE status = 'OPEN' AND created_at <= now() - interval '24 hours'), 0) AS due_minor,
                       count(*) FILTER (WHERE status = 'OPEN' AND created_at > now() - interval '24 hours') AS recent_count,
                       count(*) FILTER (WHERE status = 'RECOVERED' AND recovered_at > now() - interval '30 days') AS recovered_count,
                       coalesce(sum(total_minor) FILTER (WHERE status = 'RECOVERED' AND recovered_at > now() - interval '30 days'), 0) AS recovered_minor,
                       count(*) FILTER (WHERE created_at > now() - interval '30 days' AND (status = 'RECOVERED' OR created_at <= now() - interval '24 hours')) AS decided
                FROM commerce.abandoned_carts WHERE tenant_id = :t
                """).param("t", tenantId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        long decided = ((Number) r.get("decided")).longValue(), rec = ((Number) r.get("recovered_count")).longValue();
        m.put("recoveryRatePercent", decided == 0 ? 0 : Math.round(rec * 1000.0 / decided) / 10.0);
        m.remove("decided");
        return m;
    }

    private Map<String, Object> cart(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM commerce.abandoned_carts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Cart not found"));
    }

    public Map<String, Object> dismiss(UUID tenantId, UUID id) {
        cart(tenantId, id);
        jdbc.sql("UPDATE commerce.abandoned_carts SET status = 'DISMISSED', updated_at = now() WHERE id = :i AND tenant_id = :t AND status = 'OPEN'").param("i", id).param("t", tenantId).update();
        return Map.of("dismissed", true);
    }

    /** The owner opened WhatsApp or phoned: remember it so the list shows who was already contacted. */
    public Map<String, Object> markContacted(UUID tenantId, UUID id) {
        cart(tenantId, id);
        jdbc.sql("UPDATE commerce.abandoned_carts SET contacted_at = now() WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        return Map.of("contacted", true);
    }

    String whatsappUrl(UUID tenantId, String phone, String name, String extra) {
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        String text = "أهلاً " + (name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0] + " ") + "👋 {معاك|معاكي} " + shop + ". لاحظنا إن سلتك لسه مستنياك، {تحب|تحبي} نكمّل الطلب؟ " + urls.baseUrl(host) + "/cart" + (extra == null ? "" : " " + extra);
        return "https://wa.me/" + phone.replaceAll("\\D", "") + "?text=" + URLEncoder.encode(genders.forPhone(tenantId, phone).apply(text), StandardCharsets.UTF_8);
    }

    // ---- reminders ---------------------------------------------------------------------------------------------------------

    /** Owner pressed "send reminder". Free inside the 24 hour window; paid only when the owner allowed it. */
    public Map<String, Object> sendReminder(UUID tenantId, UUID actor, UUID id) {
        var c = cart(tenantId, id);
        if (!"OPEN".equals(c.get("status"))) throw BusinessException.conflict("CART_CLOSED", "This cart is no longer open");
        String result = remind(tenantId, c, Instant.now(), true);
        audit.record(actor, tenantId, "CART_REMINDER", "abandoned_cart", id, "{\"result\":\"" + result + "\"}");
        if ("NOT_SENT_WINDOW".equals(result)) throw BusinessException.conflict("WINDOW_CLOSED", "The customer has not written to you in the last 24 hours, so a free message is not possible. Contact them yourself, or allow paid WhatsApp messages in the recovery settings.");
        if ("NO_CREDIT".equals(result)) throw BusinessException.conflict("NO_CREDIT", "Your WhatsApp credit is not enough");
        if ("NOT_APPROVED".equals(result)) throw BusinessException.conflict("TEMPLATE_NOT_APPROVED", "The reminder template is not approved by Meta yet");
        if ("FAILED".equals(result) || "NO_CHANNEL".equals(result)) throw BusinessException.conflict("SEND_FAILED", "The message could not be sent. Check that WhatsApp is connected.");
        return Map.of("result", result);
    }

    private String remind(UUID tenantId, Map<String, Object> c, Instant now, boolean manual) {
        var ch = channels.whatsappChannel(tenantId);
        UUID cartId = (UUID) c.get("id");
        String phone = ((String) c.get("phone")).replaceAll("\\D", "");
        String name = (String) c.get("name");
        var settings = settings(tenantId);
        if ("FREE".equals(settings.get("sendMode"))) {   // the shop chose its own linked number: a normal free message, no window, no template, no charge; never the paid route instead
            if (!evolution.connected(tenantId)) return manual ? "NO_CHANNEL" : finish(cartId, "NO_CHANNEL");
            String url = urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/cart";
            String cp = (String) settings.get("couponCode");
            String who = name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
            String text = "أهلاً " + who + " 👋 سلتك لسه مستنياك. {تحب|تحبي} أكمّل {معاك|معاكي} الطلب؟ " + url + (cp == null ? "" : "\n{استخدم|استخدمي} كود الخصم " + cp);
            return finish(cartId, evolution.sendFree(tenantId, phone, genders.forPhone(tenantId, phone).apply(text)) ? "SENT_FREE" : "FAILED");
        }
        if (ch == null) return finish(cartId, "NO_CHANNEL");
        boolean windowOpen = jdbc.sql("SELECT count(*) FROM chat.wa_windows WHERE tenant_id = :t AND number = :n AND last_inbound_at > :since").param("t", tenantId).param("n", phone)
                .param("since", Timestamp.from(now.minus(Duration.ofHours(24)))).query(Long.class).single() > 0;
        String coupon = (String) settings.get("couponCode");
        String link = urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/cart";
        String hello = name == null || name.isBlank() ? "" : name.trim().split("\\s+")[0];
        if (windowOpen) {
            String text = "أهلاً " + hello + " 👋 سلتك لسه مستنياك. {تحب|تحبي} أكمّل {معاك|معاكي} الطلب؟ " + link + (coupon == null ? "" : "\n{استخدم|استخدمي} كود الخصم " + coupon);
            try {
                graph.post(ch.phoneId() + "/messages", ch.token(), Map.of("messaging_product", "whatsapp", "to", phone, "type", "text", "text", Map.of("body", genders.forPhone(tenantId, phone).apply(text))));
                return finish(cartId, "SENT_FREE");
            } catch (MetaGraph.MetaException e) {
                log.warn("Cart reminder failed: {}", e.getMessage());
                return finish(cartId, "FAILED");
            }
        }
        if (!Boolean.TRUE.equals(settings.get("allowPaid"))) return manual ? "NOT_SENT_WINDOW" : finish(cartId, "NOT_SENT_WINDOW");
        if (!"APPROVED".equals(jdbc.sql("SELECT status FROM chat.wa_templates WHERE tenant_id = :t AND name = :n").param("t", tenantId).param("n", TEMPLATE).query(String.class).optional().orElse(null)))
            return manual ? "NOT_APPROVED" : finish(cartId, "NOT_APPROVED");
        String ref = "wa-cart-" + cartId;
        if (!wallet.tryDebit(tenantId, priceMinor, ref, "WhatsApp cart reminder")) return manual ? "NO_CREDIT" : finish(cartId, "NO_CREDIT");
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        String third = link + (coupon == null ? "" : " (كود الخصم " + coupon + ")");
        Map<String, Object> msg = Map.of("messaging_product", "whatsapp", "to", phone, "type", "template", "template", Map.of("name", TEMPLATE, "language", Map.of("code", "ar"),
                "components", List.of(Map.of("type", "body", "parameters", List.of(p(hello.isEmpty() ? "-" : hello), p(shop), p(third))))));
        try {
            graph.post(ch.phoneId() + "/messages", ch.token(), msg);
            return finish(cartId, "SENT_PAID");
        } catch (MetaGraph.MetaException e) {
            wallet.credit(tenantId, priceMinor, "REFUND", ref, "Message not delivered to Meta", null);
            return finish(cartId, "FAILED");
        }
    }

    private static Map<String, Object> p(String t) { return Map.of("type", "text", "text", t.length() > 120 ? t.substring(0, 120) : t); }

    private String finish(UUID cartId, String status) {
        jdbc.sql("UPDATE commerce.abandoned_carts SET reminder_status = :s, reminder_sent_at = CASE WHEN :s IN ('SENT_FREE','SENT_PAID') THEN now() ELSE reminder_sent_at END WHERE id = :i").param("s", status).param("i", cartId).update();
        return status;
    }

    /** Registers the paid reminder template with Meta (the owner presses a button; nothing is sent or charged by this). */
    public Map<String, Object> registerTemplate(UUID tenantId) {
        var ch = channels.whatsappChannel(tenantId);
        if (ch == null) throw BusinessException.badRequest("NO_WHATSAPP", "Connect your WhatsApp number first");
        if (ch.wabaId() == null) throw BusinessException.badRequest("NO_WABA", "Add your WhatsApp Business Account ID in the channel details first");
        Map<String, Object> body = Map.of("name", TEMPLATE, "category", "MARKETING", "language", "ar", "components", List.of(Map.of("type", "BODY",
                "text", "أهلاً {{1}}، سلتك في {{2}} لسه مستنياك 🛒 كمّل طلبك من هنا: {{3}}", "example", Map.of("body_text", List.of(List.of("أحمد", "متجر النور", "https://shop.example/cart"))))));
        String status = "PENDING";
        String reason = null;
        try { graph.post(ch.wabaId() + "/message_templates", ch.token(), body); } catch (MetaGraph.MetaException e) {
            if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("already")) { status = "REJECTED"; reason = e.getMessage(); }
        }
        try {
            Map<String, Object> res = graph.get(ch.wabaId() + "/message_templates?name=" + TEMPLATE + "&fields=name,status,rejected_reason", ch.token());
            if (res.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> t) status = String.valueOf(t.get("status"));
        } catch (MetaGraph.MetaException ignored) { /* the status stays as it was */ }
        jdbc.sql("INSERT INTO chat.wa_templates (tenant_id, name, status, rejected_reason) VALUES (:t, :n, :s, :r) ON CONFLICT (tenant_id, name) DO UPDATE SET status = :s, rejected_reason = :r, updated_at = now()")
                .param("t", tenantId).param("n", TEMPLATE).param("s", status).param("r", reason == null ? null : reason.substring(0, Math.min(300, reason.length()))).update();
        return Map.of("status", status);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 150_000)
    public void job() {
        if (!scheduler) return;
        try { runDue(Instant.now()); } catch (RuntimeException e) { log.warn("Cart recovery run failed", e); }
    }

    /** One pass: remind each open cart that has waited long enough, once. Returns how many reminders went out. Public for tests. */
    public int runDue(Instant now) {
        jdbc.sql("UPDATE commerce.abandoned_carts SET status = 'EXPIRED' WHERE status = 'OPEN' AND created_at < :c").param("c", Timestamp.from(now.minus(Duration.ofDays(14)))).update();
        int sent = 0;
        for (var c : jdbc.sql("""
                SELECT a.* FROM commerce.abandoned_carts a JOIN commerce.cart_settings s ON s.tenant_id = a.tenant_id AND s.recovery_enabled
                WHERE a.status = 'OPEN' AND a.reminder_sent_at IS NULL AND a.created_at <= CAST(:now AS timestamptz) - make_interval(hours => s.remind_after_hours) AND a.created_at > CAST(:old AS timestamptz)
                  AND (a.reminder_status IS NULL OR a.reminder_status IN ('NOT_SENT_WINDOW','NO_CREDIT','NOT_APPROVED','NO_CHANNEL')) ORDER BY a.created_at LIMIT 200
                """).param("now", Timestamp.from(now)).param("old", Timestamp.from(now.minus(Duration.ofDays(14)))).query().listOfRows()) {
            try {
                String r = remind((UUID) c.get("tenant_id"), c, now, false);
                if (r.startsWith("SENT")) sent++;
            } catch (RuntimeException e) {
                log.warn("Cart reminder failed: {}", e.getMessage());
            }
        }
        return sent;
    }
}
