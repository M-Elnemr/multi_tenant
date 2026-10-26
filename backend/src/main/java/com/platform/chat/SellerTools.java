package com.platform.chat;

import com.platform.billing.EntitlementService;
import com.platform.chat.AiProvider.ToolDef;
import com.platform.commerce.StoreFulfillmentService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The seller's extra skills: order status, related products, delivery quote, coupon check, customer memory, follow-ups, back-in-stock alerts, and the
 * silent "I could not answer this" flag for the owner. Every tool is read-only except the small notes it writes, and all of them are scoped to one shop.
 */
@Component
public class SellerTools {
    static final List<String> META = List.of("WHATSAPP", "INSTAGRAM", "FACEBOOK");

    private final JdbcClient jdbc;
    private final StoreFulfillmentService fulfilment;
    private final EntitlementService ent;
    private final long defaultMonthlyCap;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Published when the assistant hands the owner a question it could not answer: {@link QuestionService} tells the owner. */
    public record QuestionAsked(UUID tenantId, long id) {}

    public SellerTools(JdbcClient jdbc, StoreFulfillmentService fulfilment, EntitlementService ent, @Value("${app.ai.monthly-message-cap:5000}") long defaultMonthlyCap,
                       org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.fulfilment = fulfilment;
        this.ent = ent;
        this.defaultMonthlyCap = defaultMonthlyCap;
    }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> str(String d) { return obj("type", "STRING", "description", d); }
    private static Map<String, Object> num(String d) { return obj("type", "NUMBER", "description", d); }

    List<ToolDef> defs() {
        return List.of(
                new ToolDef("order_status", "Status of an existing order. Needs the order number AND the phone number on the order.",
                        obj("type", "OBJECT", "properties", obj("orderNumber", str("The order number the customer has"), "phone", str("The phone number used on the order")), "required", List.of("orderNumber", "phone"))),
                new ToolDef("recommend_related", "Other in-stock products from the same category as a product: use for one honest add-on or a cheaper or better alternative.",
                        obj("type", "OBJECT", "properties", obj("slug", str("Product slug from search_products")), "required", List.of("slug"))),
                new ToolDef("delivery_quote", "Delivery fee, the order amount above which delivery is free, and days to one governorate. Quote the fee together with the free-delivery condition.",
                        obj("type", "OBJECT", "properties", obj("governorateCode", str("Governorate code from the SHOP FACTS list")), "required", List.of("governorateCode"))),
                new ToolDef("check_coupon", "Whether a coupon code is valid and what it gives. Tell the customer only what this returns.",
                        obj("type", "OBJECT", "properties", obj("code", str("The coupon code the customer has")), "required", List.of("code"))),
                new ToolDef("remember_customer", "Save one short fact about this customer for next time (name, size, taste, who they buy for). Never save phone numbers, addresses or payment details.",
                        obj("type", "OBJECT", "properties", obj("note", str("One short fact")), "required", List.of("note"))),
                new ToolDef("flag_unanswered", "Ask the shop owner a question you cannot answer from your tools (material, policy, a detail only the shop knows). The owner is notified and answers in the dashboard; you then message the customer the answer yourself in this same chat. Write the question so the owner can answer it without reading the chat (name the product).",
                        obj("type", "OBJECT", "properties", obj("question", str("What the customer asked, complete, naming the product")), "required", List.of("question"))),
                new ToolDef("schedule_follow_up", "Promise one polite follow-up message in this same chat after some hours (only on WhatsApp, Instagram and Messenger).",
                        obj("type", "OBJECT", "properties", obj("hours", num("In how many hours, 1 to 48"), "message", str("The message to send, in the customer's language")), "required", List.of("hours", "message"))),
                new ToolDef("notify_back_in_stock", "Tell this customer in this chat when an out-of-stock variant is available again (WhatsApp, Instagram and Messenger only).",
                        obj("type", "OBJECT", "properties", obj("variantId", str("Variant id from get_product")), "required", List.of("variantId")))
        );
    }

    boolean handles(String name) { return defs().stream().anyMatch(d -> d.name().equals(name)); }

    @SuppressWarnings("unchecked")
    Map<String, Object> run(String tool, UUID tenantId, UUID conv, String channel, String externalId, Map<String, Object> a) {
        return switch (tool) {
            case "order_status" -> orderStatus(tenantId, text(a, "orderNumber"), text(a, "phone"));
            case "recommend_related" -> related(tenantId, text(a, "slug"));
            case "delivery_quote" -> delivery(tenantId, text(a, "governorateCode"));
            case "check_coupon" -> coupon(tenantId, text(a, "code"));
            case "remember_customer" -> remember(tenantId, channel, externalId, text(a, "note"));
            case "flag_unanswered" -> flag(tenantId, conv, channel, text(a, "question"));
            case "schedule_follow_up" -> followUp(tenantId, conv, channel, a.get("hours") instanceof Number n ? n.intValue() : 24, text(a, "message"));
            case "notify_back_in_stock" -> backInStock(tenantId, conv, channel, text(a, "variantId"));
            default -> obj("error", "Unknown tool");
        };
    }

    private static String text(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private Map<String, Object> orderStatus(UUID t, String number, String phone) {
        if (number == null || phone == null) return obj("error", "Ask for the order number and the phone number used on the order");
        String d = digits(phone);
        if (d.length() < 9) return obj("error", "Ask for the full phone number used on the order");
        var r = jdbc.sql("SELECT order_number, status, payment_status, fulfillment_status, total_minor, currency, created_at, customer_phone_snapshot FROM commerce.orders WHERE tenant_id = :t AND upper(order_number) = upper(:n)")
                .param("t", t).param("n", number.replace("#", "")).query().listOfRows().stream().findFirst().orElse(null);
        // The same answer for "no such order" and "wrong phone": the tool must not confirm that an order number exists.
        if (r == null || !digits((String) r.get("customer_phone_snapshot")).endsWith(d.substring(d.length() - 9)))
            return obj("error", "No order matches that number and phone. Ask the customer to check both.");
        return obj("orderNumber", r.get("order_number"), "status", r.get("status"), "payment", r.get("payment_status"), "fulfilment", r.get("fulfillment_status"),
                "total", ChatService.major(r.get("total_minor")), "currency", r.get("currency"), "placedAt", String.valueOf(r.get("created_at")));
    }

    private Map<String, Object> related(UUID t, String slug) {
        if (slug == null) return obj("error", "Give the product slug");
        var rows = jdbc.sql("""
                SELECT p.name, p.slug, min(v.price_minor) AS price_minor, p.currency FROM commerce.products p
                JOIN commerce.product_variants v ON v.product_id = p.id AND v.status = 'ACTIVE'
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND p.slug <> :s AND p.category_id IS NOT NULL
                  AND p.category_id = (SELECT category_id FROM commerce.products WHERE tenant_id = :t AND slug = :s)
                  AND EXISTS (SELECT 1 FROM commerce.inventory_items i JOIN commerce.product_variants pv ON pv.id = i.variant_id WHERE pv.product_id = p.id AND i.quantity_on_hand - i.quantity_reserved > 0)
                GROUP BY p.id ORDER BY p.created_at DESC LIMIT 4
                """).param("t", t).param("s", slug).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) out.add(obj("name", r.get("name"), "slug", r.get("slug"), "price", ChatService.major(r.get("price_minor"))));
        return obj("related", out, "count", out.size());
    }

    /** The delivery zone of this shop that serves a governorate code, or null. */
    Map<String, Object> zoneFor(UUID t, String gov) {
        if (gov == null) return null;
        for (var z : fulfilment.zones(t, true)) if (z.get("governorateCodes") instanceof List<?> l && l.contains(gov.toUpperCase())) return z;
        return null;
    }

    private Map<String, Object> delivery(UUID t, String gov) {
        if (gov == null) return obj("error", "Give the governorate code");
        String code = gov.toUpperCase();
        for (var z : fulfilment.zones(t, true)) {
            if (z.get("governorateCodes") instanceof List<?> l && l.contains(code))
                return obj("zone", z.get("name"), "fee", ChatService.major(z.get("feeMinor")), "freeAbove", z.get("freeAboveMinor") == null ? null : ChatService.major(z.get("freeAboveMinor")),
                        "cashOnDeliveryFee", ChatService.major(z.get("codFeeMinor")), "days", z.get("etaMinDays") + "-" + z.get("etaMaxDays"),
                        "say", "Delivery here costs the fee above" + (z.get("freeAboveMinor") == null ? "" : " and is FREE for orders of " + ChatService.major(z.get("freeAboveMinor")) + " or more") + ". State both facts together; the real delivery cost of this customer's order is in the order summary the system sends, never add it up yourself.");
        }
        return obj("error", "The shop has no delivery zone for that governorate. Say so honestly and offer pickup if the shop has it.");
    }

    private Map<String, Object> coupon(UUID t, String code) {
        if (code == null) return obj("error", "Ask for the coupon code");
        var c = jdbc.sql("SELECT discount_type, value, min_order_minor, starts_at, ends_at, max_redemptions, redemptions, is_active FROM commerce.coupons WHERE tenant_id = :t AND upper(code) = upper(:c)")
                .param("t", t).param("c", code).query().listOfRows().stream().findFirst().orElse(null);
        Instant now = Instant.now();
        if (c == null || !Boolean.TRUE.equals(c.get("is_active"))
                || (c.get("starts_at") instanceof Timestamp s && s.toInstant().isAfter(now)) || (c.get("ends_at") instanceof Timestamp e && e.toInstant().isBefore(now))
                || (c.get("max_redemptions") instanceof Number m && ((Number) c.get("redemptions")).intValue() >= m.intValue()))
            return obj("valid", false, "message", "This code is not valid. Say so kindly; do not offer a substitute discount unless it is within your allowed maximum.");
        boolean pct = "PERCENT".equals(c.get("discount_type"));
        return obj("valid", true, "gives", pct ? ((Number) c.get("value")).longValue() + "% off" : ChatService.major(c.get("value")) + " off", "minimumOrder", ChatService.major(c.get("min_order_minor")),
                "next", "Pass this code as couponCode to prepare_order; the real discount is calculated there.");
    }

    String memory(UUID t, String channel, String externalId) {
        return jdbc.sql("SELECT notes FROM chat.customer_memory WHERE tenant_id = :t AND channel = :c AND external_id = :e").param("t", t).param("c", channel).param("e", externalId).query(String.class).optional().orElse("");
    }

    private Map<String, Object> remember(UUID t, String channel, String externalId, String note) {
        if (note == null) return obj("error", "Give a short note");
        String n = note.length() > 200 ? note.substring(0, 200) : note;
        if (n.replaceAll("\\D", "").length() >= 8) return obj("error", "Do not save phone numbers or other identifiers");
        String old = memory(t, channel, externalId);
        if (old.contains(n)) return obj("saved", true);
        String merged = (old.isEmpty() ? "" : old + "\n") + "- " + n;
        if (merged.length() > 1500) merged = merged.substring(merged.length() - 1500);
        jdbc.sql("INSERT INTO chat.customer_memory (tenant_id, channel, external_id, notes) VALUES (:t, :c, :e, :n) ON CONFLICT (tenant_id, channel, external_id) DO UPDATE SET notes = :n, updated_at = now()")
                .param("t", t).param("c", channel).param("e", externalId).param("n", merged).update();
        return obj("saved", true);
    }

    private Map<String, Object> flag(UUID t, UUID conv, String channel, String q) {
        if (q == null) return obj("error", "Give the question");
        String question = q.length() > 600 ? q.substring(0, 600) : q;
        // the same question asked again while it is still open is not a second question for the owner
        boolean again = jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.unanswered WHERE tenant_id = :t AND conversation_id = :c AND status = 'OPEN' AND lower(question) = lower(:q))")
                .param("t", t).param("c", conv).param("q", question).query(Boolean.class).single();
        if (!again) {
            long id = jdbc.sql("INSERT INTO chat.unanswered (tenant_id, conversation_id, question) VALUES (:t, :c, :q) RETURNING id").param("t", t).param("c", conv).param("q", question).query(Long.class).single();
            try { events.publishEvent(new QuestionAsked(t, id)); } catch (RuntimeException e) { /* telling the owner must never break the customer's chat */ }
        }
        boolean web = !META.contains(channel);
        return obj("asked", true, "next", web
                ? "Tell the customer you asked the shop team and the answer will appear right here in this chat when they come back to it; keep helping with what you know meanwhile. Do not invent the answer."
                : "Tell the customer you asked the shop team and you will message them the answer right here in this chat as soon as they reply; keep helping with what you know meanwhile. Do not invent the answer and do not ask the same thing again.");
    }

    private Map<String, Object> followUp(UUID t, UUID conv, String channel, int hours, String message) {
        if (!META.contains(channel)) return obj("error", "A follow-up can only be sent on WhatsApp, Instagram or Messenger. Invite the customer to message again when ready.");
        if (message == null) return obj("error", "Give the follow-up message");
        if (hours < 1 || hours > 48) return obj("error", "Hours must be between 1 and 48");
        if (jdbc.sql("SELECT count(*) FROM chat.follow_ups WHERE conversation_id = :c AND status = 'PENDING'").param("c", conv).query(Long.class).single() > 0) return obj("scheduled", true, "note", "A follow-up is already scheduled.");
        jdbc.sql("INSERT INTO chat.follow_ups (tenant_id, conversation_id, due_at, body) VALUES (:t, :c, :d, :b)").param("t", t).param("c", conv)
                .param("d", Timestamp.from(Instant.now().plusSeconds(hours * 3600L))).param("b", message.length() > 800 ? message.substring(0, 800) : message).update();
        return obj("scheduled", true, "inHours", hours);
    }

    private Map<String, Object> backInStock(UUID t, UUID conv, String channel, String variantId) {
        if (!META.contains(channel)) return obj("error", "Alerts work only on WhatsApp, Instagram or Messenger.");
        UUID v;
        try { v = UUID.fromString(String.valueOf(variantId)); } catch (IllegalArgumentException e) { return obj("error", "Invalid variantId"); }
        if (jdbc.sql("SELECT count(*) FROM commerce.product_variants WHERE id = :v AND tenant_id = :t").param("v", v).param("t", t).query(Long.class).single() == 0) return obj("error", "Unknown variant");
        jdbc.sql("INSERT INTO chat.stock_alerts (tenant_id, conversation_id, variant_id) VALUES (:t, :c, :v) ON CONFLICT (conversation_id, variant_id) DO UPDATE SET status = 'WAITING'").param("t", t).param("c", conv).param("v", v).update();
        return obj("saved", true);
    }

    // ---- quota and usage ---------------------------------------------------------------------------------------

    private static LocalDate month() { return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1); }

    /** The shop's plan limit "ai_messages" when it has one, else the platform default. */
    public long monthlyCap(UUID t) { return ent.limit(t, "ai_messages").orElse(defaultMonthlyCap); }

    public long used(UUID t) { return jdbc.sql("SELECT ai_messages FROM chat.usage WHERE tenant_id = :t AND month = :m").param("t", t).param("m", java.sql.Date.valueOf(month())).query(Long.class).optional().orElse(0L); }

    public boolean overQuota(UUID t) { return used(t) >= monthlyCap(t); }

    public void count(UUID t) {
        jdbc.sql("INSERT INTO chat.usage (tenant_id, month, ai_messages) VALUES (:t, :m, 1) ON CONFLICT (tenant_id, month) DO UPDATE SET ai_messages = chat.usage.ai_messages + 1")
                .param("t", t).param("m", java.sql.Date.valueOf(month())).update();
    }

    public Map<String, Object> usageView(UUID t) {
        long cap = monthlyCap(t), u = used(t);
        long unanswered = jdbc.sql("SELECT count(*) FROM chat.unanswered WHERE tenant_id = :t AND NOT resolved").param("t", t).query(Long.class).single();
        return obj("usedThisMonth", u, "monthlyCap", cap, "unanswered", unanswered);
    }

    public List<Map<String, Object>> unanswered(UUID t) {
        return com.platform.shared.Rows.camel(jdbc.sql("SELECT id, question, resolved, created_at FROM chat.unanswered WHERE tenant_id = :t ORDER BY resolved, created_at DESC LIMIT 100").param("t", t).query().listOfRows());
    }

    public boolean resolve(UUID t, long id) { return jdbc.sql("UPDATE chat.unanswered SET resolved = TRUE, status = CASE WHEN status = 'OPEN' THEN 'DISMISSED' ELSE status END WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update() > 0; }
}
