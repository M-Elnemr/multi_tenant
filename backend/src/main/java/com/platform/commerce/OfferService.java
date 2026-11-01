package com.platform.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.FieldErrors;
import com.platform.shared.Rows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatic offers. The shop picks a type and fills its settings; the price is always worked out here, on the server, from the live offer rows.
 * <ul>
 *   <li>Item offers (PERCENT_OFF, FIXED_OFF, FLASH_SALE) lower each matching item's price: the best one per item wins, they never add up.</li>
 *   <li>Cart offers (BUY_X_GET_Y, BUNDLE_PRICE, TIERED_QTY, ORDER_THRESHOLD, FIRST_ORDER) are worked out on the sale prices. The best non-stackable one applies,
 *       plus every stackable one.</li>
 *   <li>FREE_SHIPPING waives delivery; GIFT_WITH_PURCHASE adds a free line while the gift is in stock.</li>
 * </ul>
 * The total discount never exceeds the subtotal.
 */
@Service
public class OfferService {
    public static final Set<String> TYPES = Set.of("PERCENT_OFF", "FIXED_OFF", "FLASH_SALE", "BUY_X_GET_Y", "BUNDLE_PRICE", "TIERED_QTY", "ORDER_THRESHOLD", "FREE_SHIPPING", "GIFT_WITH_PURCHASE", "FIRST_ORDER");
    static final Set<String> ITEM_TYPES = Set.of("PERCENT_OFF", "FIXED_OFF", "FLASH_SALE");

    public record OfferReq(String name, String type, Boolean active, Boolean draft, Instant startsAt, Instant endsAt, Integer priority, Boolean stackable,
                           String appliesTo, List<String> productIds, List<Integer> categoryIds, String badgeText, String description,
                           String bannerImage, Boolean showBanner, Map<String, Object> config, Integer maxUses) {}

    /** One cart line as the price engine sees it. */
    public record Line(UUID productId, UUID variantId, long unitPrice, int quantity) {}

    /** What the offers did to a cart. lineDiscounts is per line index (item offers + share of cart offers on those lines). */
    public record Result(long[] lineDiscounts, long itemDiscount, long cartDiscount, boolean freeShipping, List<Map<String, Object>> applied, List<Gift> gifts) {
        public long total() { return itemDiscount + cartDiscount; }
    }
    public record Gift(UUID offerId, UUID variantId, int quantity) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public OfferService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- admin -------------------------------------------------------------------------------------------------

    /**
     * Offers and coupons together for the offers screen. state: ACTIVE (running now), SCHEDULED (starts later), ENDED, PAUSED, DRAFT.
     * from/to: offers running at any time in that range.
     */
    public List<Map<String, Object>> list(UUID tenantId, String state, String type, String q, java.time.LocalDate from, java.time.LocalDate to) {
        com.platform.shared.DateRange.check(from, to);
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT o.*, o.config::text AS config_text,
                  (SELECT coalesce(json_agg(json_build_object('type', t.target_type, 'id', t.target_id)), '[]') FROM commerce.offer_targets t WHERE t.offer_id = o.id)::text AS targets_text
                FROM commerce.offers o WHERE o.tenant_id = :t AND (CAST(:q AS varchar) IS NULL OR o.name ILIKE CAST(:q AS varchar))
                AND (CAST(:to AS date) IS NULL OR o.starts_at IS NULL OR o.starts_at < CAST(:to AS date) + 1)
                AND (CAST(:from AS date) IS NULL OR o.ends_at IS NULL OR o.ends_at >= CAST(:from AS date))
                ORDER BY o.created_at DESC
                """).param("t", tenantId).param("q", like).param("from", from).param("to", to).query().listOfRows();
        for (var r : rows) out.add(view(r));
        var coupons = jdbc.sql("""
                SELECT * FROM commerce.coupons WHERE tenant_id = :t AND (CAST(:q AS varchar) IS NULL OR code ILIKE CAST(:q AS varchar) OR name ILIKE CAST(:q AS varchar))
                AND (CAST(:to AS date) IS NULL OR starts_at IS NULL OR starts_at < CAST(:to AS date) + 1)
                AND (CAST(:from AS date) IS NULL OR ends_at IS NULL OR ends_at >= CAST(:from AS date))
                ORDER BY created_at DESC
                """).param("t", tenantId).param("q", like).param("from", from).param("to", to).query().listOfRows();
        for (var c : coupons) out.add(couponView(c));
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt"))).reversed());
        return out.stream().filter(m -> state == null || state.isBlank() || state.equalsIgnoreCase((String) m.get("state")))
                .filter(m -> type == null || type.isBlank() || type.equalsIgnoreCase((String) m.get("type"))).toList();
    }

    /** Counts per state for the tabs, and what the offers brought in. */
    public Map<String, Object> stats(UUID tenantId) {
        var all = list(tenantId, null, null, null, null, null);
        Map<String, Long> byState = new LinkedHashMap<>();
        for (String s : List.of("ACTIVE", "SCHEDULED", "PAUSED", "DRAFT", "ENDED")) byState.put(s, all.stream().filter(m -> s.equals(m.get("state"))).count());
        var sums = jdbc.sql("""
                SELECT count(*) AS orders, coalesce(sum(total_minor), 0) AS revenue, coalesce(sum(discount_minor), 0) AS discount FROM commerce.orders
                WHERE tenant_id = :t AND created_at > now() - interval '30 days' AND status NOT IN ('CANCELLED','RETURNED','REFUNDED')
                AND (jsonb_array_length(applied_offers) > 0 OR coupon_code IS NOT NULL)
                """).param("t", tenantId).query().singleRow();
        return Map.of("states", byState, "orders30d", sums.get("orders"), "revenue30dMinor", sums.get("revenue"), "discount30dMinor", sums.get("discount"));
    }

    public Map<String, Object> get(UUID tenantId, UUID id) {
        var r = jdbc.sql("""
                SELECT o.*, o.config::text AS config_text,
                  (SELECT coalesce(json_agg(json_build_object('type', t.target_type, 'id', t.target_id)), '[]') FROM commerce.offer_targets t WHERE t.offer_id = o.id)::text AS targets_text
                FROM commerce.offers o WHERE o.id = :i AND o.tenant_id = :t
                """).param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Offer not found"));
        Map<String, Object> v = view(r);
        // names for the chosen products, so the form can show them as chips
        @SuppressWarnings("unchecked") List<String> pids = (List<String>) v.get("productIds");
        if (!pids.isEmpty())
            v.put("products", Rows.camel(jdbc.sql("SELECT id, name FROM commerce.products WHERE tenant_id = :t AND id = ANY(string_to_array(:ids, ',')::uuid[])")
                    .param("t", tenantId).param("ids", String.join(",", pids)).query().listOfRows()));
        return v;
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, UUID actor, OfferReq r) {
        Map<String, Object> cfg = validate(tenantId, r, null);
        UUID id = jdbc.sql("""
                INSERT INTO commerce.offers (tenant_id, name, type, is_active, is_draft, starts_at, ends_at, priority, stackable, applies_to, badge_text, description, banner_image, show_banner, config, max_uses)
                VALUES (:t, :n, :ty, :a, :d, :s, :e, :p, :st, :ap, :b, :de, :bi, :sb, CAST(:c AS jsonb), :mu) RETURNING id
                """).param("t", tenantId).param("n", r.name().trim()).param("ty", r.type()).param("a", r.active() == null || r.active()).param("d", Boolean.TRUE.equals(r.draft()))
                .param("s", ts(r.startsAt())).param("e", ts(r.endsAt())).param("p", r.priority() == null ? 0 : r.priority()).param("st", Boolean.TRUE.equals(r.stackable()))
                .param("ap", appliesTo(r)).param("b", nz(r.badgeText())).param("de", nz(r.description())).param("bi", blankNull(r.bannerImage())).param("sb", Boolean.TRUE.equals(r.showBanner()))
                .param("c", write(cfg)).param("mu", r.maxUses()).query(UUID.class).single();
        targets(id, r);
        audit.record(actor, tenantId, "OFFER_CREATED", "offer", id, "{\"type\":\"" + r.type() + "\"}");
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> update(UUID tenantId, UUID actor, UUID id, OfferReq r) {
        get(tenantId, id);
        Map<String, Object> cfg = validate(tenantId, r, id);
        jdbc.sql("""
                UPDATE commerce.offers SET name = :n, type = :ty, is_active = :a, is_draft = :d, starts_at = :s, ends_at = :e, priority = :p, stackable = :st, applies_to = :ap,
                  badge_text = :b, description = :de, banner_image = :bi, show_banner = :sb, config = CAST(:c AS jsonb), max_uses = :mu, updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("n", r.name().trim()).param("ty", r.type()).param("a", r.active() == null || r.active()).param("d", Boolean.TRUE.equals(r.draft()))
                .param("s", ts(r.startsAt())).param("e", ts(r.endsAt())).param("p", r.priority() == null ? 0 : r.priority()).param("st", Boolean.TRUE.equals(r.stackable()))
                .param("ap", appliesTo(r)).param("b", nz(r.badgeText())).param("de", nz(r.description())).param("bi", blankNull(r.bannerImage())).param("sb", Boolean.TRUE.equals(r.showBanner()))
                .param("c", write(cfg)).param("mu", r.maxUses()).param("i", id).param("t", tenantId).update();
        jdbc.sql("DELETE FROM commerce.offer_targets WHERE offer_id = :i").param("i", id).update();
        targets(id, r);
        audit.record(actor, tenantId, "OFFER_UPDATED", "offer", id, null);
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> setActive(UUID tenantId, UUID actor, UUID id, boolean active) {
        if (jdbc.sql("UPDATE commerce.offers SET is_active = :a, is_draft = CASE WHEN :a THEN FALSE ELSE is_draft END, updated_at = now() WHERE id = :i AND tenant_id = :t")
                .param("a", active).param("i", id).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Offer not found");
        audit.record(actor, tenantId, active ? "OFFER_RESUMED" : "OFFER_PAUSED", "offer", id, null);
        return get(tenantId, id);
    }

    @Transactional
    public Map<String, Object> duplicate(UUID tenantId, UUID actor, UUID id) {
        get(tenantId, id);
        UUID copy = jdbc.sql("""
                INSERT INTO commerce.offers (tenant_id, name, type, is_active, is_draft, starts_at, ends_at, priority, stackable, applies_to, badge_text, description, banner_image, show_banner, config, max_uses)
                SELECT tenant_id, left(name || ' (copy)', 120), type, FALSE, TRUE, starts_at, ends_at, priority, stackable, applies_to, badge_text, description, banner_image, show_banner, config, max_uses
                FROM commerce.offers WHERE id = :i RETURNING id
                """).param("i", id).query(UUID.class).single();
        jdbc.sql("INSERT INTO commerce.offer_targets (offer_id, target_type, target_id) SELECT :c, target_type, target_id FROM commerce.offer_targets WHERE offer_id = :i")
                .param("c", copy).param("i", id).update();
        audit.record(actor, tenantId, "OFFER_CREATED", "offer", copy, "{\"copyOf\":\"" + id + "\"}");
        return get(tenantId, copy);
    }

    @Transactional
    public void delete(UUID tenantId, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM commerce.offers WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Offer not found");
        audit.record(actor, tenantId, "OFFER_DELETED", "offer", id, null);
    }

    /** Checks every field (each error under its own field) and returns the clean settings for the type. */
    private Map<String, Object> validate(UUID tenantId, OfferReq r, UUID self) {
        var fe = new FieldErrors();
        fe.when(r.name() == null || r.name().isBlank(), "name", "Give the offer a name");
        fe.when(r.name() != null && r.name().trim().length() > 120, "name", "The name is too long");
        if (r.type() == null || !TYPES.contains(r.type())) { fe.add("type", "Choose the offer type"); fe.throwIfAny(); }
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) fe.add("endsAt", "The end must be after the start");
        if ("FLASH_SALE".equals(r.type()) && r.endsAt() == null) fe.add("endsAt", "A flash sale needs an end time for its countdown");
        fe.when(r.maxUses() != null && r.maxUses() < 1, "maxUses", "Use a number above zero");
        fe.when(r.badgeText() != null && r.badgeText().length() > 40, "badgeText", "Keep the badge under 40 letters");
        String ap = appliesTo(r);
        if ("PRODUCTS".equals(ap) && (r.productIds() == null || r.productIds().isEmpty())) fe.add("productIds", "Choose at least one product");
        if ("CATEGORIES".equals(ap) && (r.categoryIds() == null || r.categoryIds().isEmpty())) fe.add("categoryIds", "Choose at least one category");
        if (r.productIds() != null && !r.productIds().isEmpty()) {
            long found = jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND id = ANY(string_to_array(:ids, ',')::uuid[])")
                    .param("t", tenantId).param("ids", String.join(",", r.productIds())).query(Long.class).single();
            if (found != new HashSet<>(r.productIds()).size()) fe.add("productIds", "One of the products was not found");
        }
        Map<String, Object> c = r.config() == null ? Map.of() : r.config();
        Map<String, Object> out = new LinkedHashMap<>();
        switch (r.type()) {
            case "PERCENT_OFF", "FLASH_SALE" -> {
                Long p = num(c, "percent");
                if (p == null || p < 1 || p > 95) fe.add("config.percent", "Use a percentage from 1 to 95");
                else out.put("percent", p);
            }
            case "FIXED_OFF" -> {
                Long a = num(c, "amountMinor");
                if (a == null || a < 1) fe.add("config.amountMinor", "Enter the amount taken off each item");
                else out.put("amountMinor", a);
            }
            case "BUY_X_GET_Y" -> {
                Long buy = num(c, "buyQty"), get = num(c, "getQty"), pct = num(c, "getPercent");
                if (buy == null || buy < 1 || buy > 50) fe.add("config.buyQty", "How many must the customer buy (1–50)");
                if (get == null || get < 1 || get > 50) fe.add("config.getQty", "How many do they get (1–50)");
                if (pct == null) pct = 100L;
                if (pct < 1 || pct > 100) fe.add("config.getPercent", "Use 100 for free, or a percentage off");
                out.put("buyQty", buy); out.put("getQty", get); out.put("getPercent", pct);
            }
            case "BUNDLE_PRICE" -> {
                Long price = num(c, "bundlePriceMinor");
                if (price == null || price < 1) fe.add("config.bundlePriceMinor", "Enter the bundle price");
                if (r.productIds() == null || r.productIds().size() < 2) fe.add("productIds", "A bundle needs at least two products");
                out.put("bundlePriceMinor", price);
            }
            case "TIERED_QTY" -> {
                List<Map<String, Object>> tiers = new ArrayList<>();
                if (c.get("tiers") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) {
                    Long min = num(m, "minQty"), pct = num(m, "percent");
                    if (min != null && pct != null && min >= 2 && pct >= 1 && pct <= 90) tiers.add(Map.of("minQty", min, "percent", pct));
                    else fe.add("config.tiers", "Each tier needs a quantity from 2 and a percentage from 1 to 90");
                }
                if (tiers.isEmpty()) fe.add("config.tiers", "Add at least one tier");
                tiers.sort(Comparator.comparingLong(m -> (Long) m.get("minQty")));
                out.put("tiers", tiers);
            }
            case "ORDER_THRESHOLD" -> {
                Long min = num(c, "minSubtotalMinor"), val = num(c, "value"), cap = num(c, "maxDiscountMinor");
                String kind = c.get("discountType") instanceof String s ? s : "PERCENT";
                if (min == null || min < 1) fe.add("config.minSubtotalMinor", "Enter the amount the customer must spend");
                if (!Set.of("PERCENT", "FIXED").contains(kind)) fe.add("config.discountType", "Choose percent or amount");
                if (val == null || val < 1 || ("PERCENT".equals(kind) && val > 90)) fe.add("config.value", "PERCENT".equals(kind) ? "Use a percentage from 1 to 90" : "Enter the amount off");
                out.put("minSubtotalMinor", min); out.put("discountType", kind); out.put("value", val);
                if (cap != null && cap > 0) out.put("maxDiscountMinor", cap);
            }
            case "FREE_SHIPPING" -> {
                Long min = num(c, "minSubtotalMinor");
                if (min != null && min < 0) fe.add("config.minSubtotalMinor", "The amount cannot be negative");
                out.put("minSubtotalMinor", min == null ? 0 : min);
                if (c.get("governorates") instanceof List<?> g) out.put("governorates", g.stream().map(String::valueOf).map(String::toUpperCase).toList());
            }
            case "GIFT_WITH_PURCHASE" -> {
                Long min = num(c, "minSubtotalMinor"), qty = num(c, "giftQty");
                String gift = c.get("giftVariantId") instanceof String s ? s : null;
                if (gift == null || gift.isBlank()) fe.add("config.giftVariantId", "Choose the gift");
                else {
                    try {
                        if (jdbc.sql("SELECT count(*) FROM commerce.product_variants WHERE id = :v AND tenant_id = :t").param("v", UUID.fromString(gift)).param("t", tenantId).query(Long.class).single() == 0)
                            fe.add("config.giftVariantId", "The gift was not found");
                    } catch (IllegalArgumentException e) { fe.add("config.giftVariantId", "Choose the gift"); }
                }
                if (min == null || min < 0) fe.add("config.minSubtotalMinor", "Enter the amount the customer must spend (0 for any order)");
                out.put("giftVariantId", gift); out.put("giftQty", qty == null || qty < 1 ? 1 : Math.min(qty, 10)); out.put("minSubtotalMinor", min);
            }
            case "FIRST_ORDER" -> {
                Long val = num(c, "value");
                String kind = c.get("discountType") instanceof String s ? s : "PERCENT";
                if (!Set.of("PERCENT", "FIXED").contains(kind)) fe.add("config.discountType", "Choose percent or amount");
                if (val == null || val < 1 || ("PERCENT".equals(kind) && val > 90)) fe.add("config.value", "PERCENT".equals(kind) ? "Use a percentage from 1 to 90" : "Enter the amount off");
                out.put("discountType", kind); out.put("value", val);
            }
            default -> { }
        }
        fe.throwIfAny();
        return out;
    }

    private void targets(UUID offerId, OfferReq r) {
        String ap = appliesTo(r);
        if (("PRODUCTS".equals(ap) || "BUNDLE_PRICE".equals(r.type())) && r.productIds() != null)
            for (String p : new HashSet<>(r.productIds()))
                jdbc.sql("INSERT INTO commerce.offer_targets (offer_id, target_type, target_id) VALUES (:o, 'PRODUCT', :i)").param("o", offerId).param("i", p).update();
        if ("CATEGORIES".equals(ap) && r.categoryIds() != null)
            for (Integer c : new HashSet<>(r.categoryIds()))
                jdbc.sql("INSERT INTO commerce.offer_targets (offer_id, target_type, target_id) VALUES (:o, 'CATEGORY', :i)").param("o", offerId).param("i", String.valueOf(c)).update();
    }

    private static String appliesTo(OfferReq r) {
        if ("BUNDLE_PRICE".equals(r.type())) return "PRODUCTS";
        String a = r.appliesTo() == null ? "ALL" : r.appliesTo().toUpperCase();
        return Set.of("ALL", "PRODUCTS", "CATEGORIES").contains(a) ? a : "ALL";
    }

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.get("id"));
        v.put("kind", "OFFER");
        v.put("name", r.get("name"));
        v.put("type", r.get("type"));
        v.put("active", r.get("is_active"));
        v.put("draft", r.get("is_draft"));
        v.put("startsAt", instant(r.get("starts_at")));
        v.put("endsAt", instant(r.get("ends_at")));
        v.put("priority", r.get("priority"));
        v.put("stackable", r.get("stackable"));
        v.put("appliesTo", r.get("applies_to"));
        v.put("badgeText", r.get("badge_text"));
        v.put("description", r.get("description"));
        v.put("bannerImage", r.get("banner_image"));
        v.put("showBanner", r.get("show_banner"));
        v.put("config", Rows.json(r.get("config_text")));
        v.put("maxUses", r.get("max_uses"));
        v.put("usageCount", r.get("usage_count"));
        v.put("discountGivenMinor", r.get("discount_given_minor"));
        v.put("createdAt", instant(r.get("created_at")));
        List<String> pids = new ArrayList<>();
        List<Integer> cids = new ArrayList<>();
        for (Object t : Rows.jsonList(r.get("targets_text"))) if (t instanceof Map<?, ?> m) {
            if ("PRODUCT".equals(m.get("type"))) pids.add(String.valueOf(m.get("id")));
            else try { cids.add(Integer.valueOf(String.valueOf(m.get("id")))); } catch (NumberFormatException ignored) { }
        }
        v.put("productIds", pids);
        v.put("categoryIds", cids);
        v.put("state", state(Boolean.TRUE.equals(r.get("is_active")), Boolean.TRUE.equals(r.get("is_draft")), (Instant) v.get("startsAt"), (Instant) v.get("endsAt"),
                r.get("max_uses") == null ? null : ((Number) r.get("max_uses")).intValue(), ((Number) r.get("usage_count")).intValue()));
        return v;
    }

    private Map<String, Object> couponView(Map<String, Object> c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.get("id"));
        v.put("kind", "COUPON");
        v.put("type", "COUPON");
        v.put("name", c.get("name") == null || ((String) c.get("name")).isBlank() ? c.get("code") : c.get("name"));
        v.put("code", c.get("code"));
        v.put("description", c.get("description"));
        v.put("active", c.get("is_active"));
        v.put("draft", false);
        v.put("startsAt", instant(c.get("starts_at")));
        v.put("endsAt", instant(c.get("ends_at")));
        v.put("config", Map.of("discountType", c.get("discount_type"), "value", c.get("value"), "minOrderMinor", c.get("min_order_minor"),
                "perCustomerLimit", c.get("per_customer_limit") == null ? 0 : c.get("per_customer_limit")));
        v.put("maxUses", c.get("max_redemptions"));
        v.put("usageCount", c.get("redemptions"));
        v.put("createdAt", instant(c.get("created_at")));
        v.put("state", state(Boolean.TRUE.equals(c.get("is_active")), false, (Instant) v.get("startsAt"), (Instant) v.get("endsAt"),
                c.get("max_redemptions") == null ? null : ((Number) c.get("max_redemptions")).intValue(), ((Number) c.get("redemptions")).intValue()));
        return v;
    }

    static String state(boolean active, boolean draft, Instant starts, Instant ends, Integer maxUses, int used) {
        Instant now = Instant.now();
        if (draft) return "DRAFT";
        if ((ends != null && !ends.isAfter(now)) || (maxUses != null && used >= maxUses)) return "ENDED";
        if (!active) return "PAUSED";
        if (starts != null && starts.isAfter(now)) return "SCHEDULED";
        return "ACTIVE";
    }

    // ---- pricing -------------------------------------------------------------------------------------------------

    private record Live(UUID id, String name, String type, boolean stackable, int priority, String appliesTo, String badge, Instant endsAt,
                        Map<String, Object> config, Set<String> products, Set<Integer> categories) {}

    /** Offers running right now. */
    private List<Live> live(UUID tenantId) {
        var rows = jdbc.sql("""
                SELECT o.id, o.name, o.type, o.stackable, o.priority, o.applies_to, o.badge_text, o.ends_at, o.config::text AS config_text,
                  (SELECT coalesce(json_agg(json_build_object('type', t.target_type, 'id', t.target_id)), '[]') FROM commerce.offer_targets t WHERE t.offer_id = o.id)::text AS targets_text
                FROM commerce.offers o WHERE o.tenant_id = :t AND o.is_active AND NOT o.is_draft AND (o.starts_at IS NULL OR o.starts_at <= now()) AND (o.ends_at IS NULL OR o.ends_at > now())
                AND (o.max_uses IS NULL OR o.usage_count < o.max_uses) ORDER BY o.priority DESC, o.created_at
                """).param("t", tenantId).query().listOfRows();
        List<Live> out = new ArrayList<>();
        for (var r : rows) {
            Set<String> ps = new HashSet<>();
            Set<Integer> cs = new HashSet<>();
            for (Object t : Rows.jsonList(r.get("targets_text"))) if (t instanceof Map<?, ?> m) {
                if ("PRODUCT".equals(m.get("type"))) ps.add(String.valueOf(m.get("id")));
                else try { cs.add(Integer.valueOf(String.valueOf(m.get("id")))); } catch (NumberFormatException ignored) { }
            }
            out.add(new Live((UUID) r.get("id"), (String) r.get("name"), (String) r.get("type"), Boolean.TRUE.equals(r.get("stackable")), ((Number) r.get("priority")).intValue(),
                    (String) r.get("applies_to"), (String) r.get("badge_text"), instant(r.get("ends_at")), Rows.json(r.get("config_text")), ps, cs));
        }
        return out;
    }

    /** Each product's category and the categories above it (an offer on «ملابس» covers «ملابس رجالي»). */
    private Map<UUID, Set<Integer>> categoriesOf(UUID tenantId, java.util.Collection<UUID> productIds) {
        Map<UUID, Set<Integer>> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        for (var r : jdbc.sql("""
                SELECT p.id AS product_id, a.id AS cat FROM commerce.products p JOIN commerce.taxonomy t ON t.id = p.taxonomy_id JOIN commerce.taxonomy a ON t.path LIKE a.path || '%'
                WHERE p.tenant_id = :t AND p.id = ANY(string_to_array(:ids, ',')::uuid[])
                """).param("t", tenantId).param("ids", String.join(",", productIds.stream().map(UUID::toString).toList())).query().listOfRows())
            out.computeIfAbsent((UUID) r.get("product_id"), k -> new HashSet<>()).add(((Number) r.get("cat")).intValue());
        return out;
    }

    private static boolean covers(Live o, UUID productId, Map<UUID, Set<Integer>> cats) {
        return switch (o.appliesTo()) {
            case "PRODUCTS" -> o.products().contains(productId.toString());
            case "CATEGORIES" -> cats.getOrDefault(productId, Set.of()).stream().anyMatch(o.categories()::contains);
            default -> true;
        };
    }

    /** The money one item offer takes off one unit. */
    private static long unitOff(Live o, long price) {
        return switch (o.type()) {
            case "PERCENT_OFF", "FLASH_SALE" -> price * num(o.config(), "percent") / 100;
            case "FIXED_OFF" -> Math.min(price, num(o.config(), "amountMinor"));
            default -> 0;
        };
    }

    /** Prices a cart. customerId may be null (a first order is then decided by the phone's earlier orders, which the caller has already folded into customerId). */
    public Result price(UUID tenantId, UUID customerId, List<Line> lines, String governorate) {
        long[] perLine = new long[lines.size()];
        List<Map<String, Object>> applied = new ArrayList<>();
        List<Gift> gifts = new ArrayList<>();
        List<Live> offers = live(tenantId);
        if (offers.isEmpty() || lines.isEmpty()) return new Result(perLine, 0, 0, false, applied, gifts);
        var cats = categoriesOf(tenantId, lines.stream().map(Line::productId).collect(java.util.stream.Collectors.toSet()));

        // 1) item offers: best one per line
        long itemDiscount = 0;
        Map<UUID, Long> itemByOffer = new LinkedHashMap<>();
        long[] salePrice = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            Live best = null;
            long bestOff = 0;
            for (Live o : offers) if (ITEM_TYPES.contains(o.type()) && covers(o, l.productId(), cats)) {
                long off = unitOff(o, l.unitPrice());
                if (off > bestOff) { bestOff = off; best = o; }
            }
            salePrice[i] = l.unitPrice() - bestOff;
            if (best != null) {
                perLine[i] = bestOff * l.quantity();
                itemDiscount += perLine[i];
                itemByOffer.merge(best.id(), perLine[i], Long::sum);
            }
        }
        for (var e : itemByOffer.entrySet()) {
            Live o = offers.stream().filter(x -> x.id().equals(e.getKey())).findFirst().orElseThrow();
            applied.add(appliedRow(o, e.getValue()));
        }
        long subtotalAfterItems = 0;
        for (int i = 0; i < lines.size(); i++) subtotalAfterItems += salePrice[i] * lines.get(i).quantity();

        // 2) cart offers on the sale prices
        record Cand(Live o, long amount, long[] split) {}
        List<Cand> cands = new ArrayList<>();
        for (Live o : offers) {
            if (ITEM_TYPES.contains(o.type()) || "FREE_SHIPPING".equals(o.type()) || "GIFT_WITH_PURCHASE".equals(o.type())) continue;
            long[] split = new long[lines.size()];
            long amount = switch (o.type()) {
                case "BUY_X_GET_Y" -> buyXgetY(o, lines, salePrice, cats, split);
                case "BUNDLE_PRICE" -> bundle(o, lines, salePrice, split);
                case "TIERED_QTY" -> tiered(o, lines, salePrice, cats, split);
                case "ORDER_THRESHOLD" -> threshold(o, subtotalAfterItems);
                case "FIRST_ORDER" -> firstOrder(tenantId, customerId, o, subtotalAfterItems);
                default -> 0;
            };
            if (amount > 0) cands.add(new Cand(o, amount, split));
        }
        long cartDiscount = 0;
        Cand bestSolo = cands.stream().filter(c -> !c.o().stackable()).max(Comparator.comparingLong(Cand::amount)).orElse(null);
        for (Cand c : cands) if (c.o().stackable() || c == bestSolo) {
            long room = subtotalAfterItems - cartDiscount;
            long take = Math.min(c.amount(), Math.max(room, 0));
            if (take <= 0) continue;
            cartDiscount += take;
            for (int i = 0; i < lines.size(); i++) perLine[i] += Math.min(c.split()[i], take);
            applied.add(appliedRow(c.o(), take));
        }

        // 3) delivery and gifts
        boolean freeShipping = false;
        long paid = subtotalAfterItems - cartDiscount;
        for (Live o : offers) {
            if ("FREE_SHIPPING".equals(o.type()) && paid >= numOr(o.config(), "minSubtotalMinor", 0) && cartCovered(o, lines, cats)) {
                Object gs = o.config().get("governorates");
                if (gs instanceof List<?> g && !g.isEmpty() && (governorate == null || !g.contains(governorate.toUpperCase()))) continue;
                if (!freeShipping) { freeShipping = true; applied.add(appliedRow(o, 0)); }
            }
            if ("GIFT_WITH_PURCHASE".equals(o.type()) && paid >= numOr(o.config(), "minSubtotalMinor", 0) && cartCovered(o, lines, cats) && o.config().get("giftVariantId") instanceof String g) {
                UUID gv = UUID.fromString(g);
                int qty = (int) numOr(o.config(), "giftQty", 1);
                long avail = jdbc.sql("""
                        SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                        JOIN commerce.product_variants v ON v.id = i.variant_id AND v.status = 'ACTIVE' WHERE i.variant_id = :v AND i.tenant_id = :t
                        """).param("v", gv).param("t", tenantId).query(Long.class).single();
                long inCart = lines.stream().filter(l -> l.variantId().equals(gv)).mapToLong(Line::quantity).sum();
                if (avail - inCart >= qty) { gifts.add(new Gift(o.id(), gv, qty)); applied.add(appliedRow(o, 0)); }
            }
        }
        return new Result(perLine, itemDiscount, cartDiscount, freeShipping, applied, gifts);
    }

    /** "Applies to some products" for a cart offer means at least one of them is in the cart. */
    private static boolean cartCovered(Live o, List<Line> lines, Map<UUID, Set<Integer>> cats) {
        if ("ALL".equals(o.appliesTo())) return true;
        return lines.stream().anyMatch(l -> covers(o, l.productId(), cats));
    }

    private static long buyXgetY(Live o, List<Line> lines, long[] sale, Map<UUID, Set<Integer>> cats, long[] split) {
        int buy = (int) numOr(o.config(), "buyQty", 1), get = (int) numOr(o.config(), "getQty", 1);
        long pct = numOr(o.config(), "getPercent", 100);
        List<long[]> units = new ArrayList<>();   // {price, lineIndex}
        for (int i = 0; i < lines.size(); i++) if (covers(o, lines.get(i).productId(), cats)) for (int k = 0; k < lines.get(i).quantity(); k++) units.add(new long[]{sale[i], i});
        units.sort((a, b) -> Long.compare(b[0], a[0]));    // dearest first: in each group the cheapest ones are the gift
        int group = buy + get, groups = units.size() / group;
        long total = 0;
        for (int gI = 0; gI < groups; gI++)
            for (int k = gI * group + buy; k < gI * group + group; k++) {
                long off = units.get(k)[0] * pct / 100;
                total += off;
                split[(int) units.get(k)[1]] += off;
            }
        return total;
    }

    private static long bundle(Live o, List<Line> lines, long[] sale, long[] split) {
        long bundlePrice = num(o.config(), "bundlePriceMinor");
        int sets = Integer.MAX_VALUE;
        long setValue = 0;
        Map<String, Integer> cheapestLine = new HashMap<>();
        for (String pid : o.products()) {
            int qty = 0;
            Integer best = null;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).productId().toString().equals(pid)) {
                qty += lines.get(i).quantity();
                if (best == null || sale[i] < sale[best]) best = i;
            }
            if (best == null) return 0;
            sets = Math.min(sets, qty);
            setValue += sale[best];
            cheapestLine.put(pid, best);
        }
        if (o.products().isEmpty() || sets == 0 || setValue <= bundlePrice) return 0;
        long perSet = setValue - bundlePrice, total = perSet * sets;
        // spread the saving over the lines by value, for the per-item discount column
        long given = 0;
        List<Integer> idx = new ArrayList<>(cheapestLine.values());
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k);
            long share = k == idx.size() - 1 ? total - given : total * sale[i] / setValue;
            split[i] += share;
            given += share;
        }
        return total;
    }

    private static long tiered(Live o, List<Line> lines, long[] sale, Map<UUID, Set<Integer>> cats, long[] split) {
        long qty = 0, value = 0;
        for (int i = 0; i < lines.size(); i++) if (covers(o, lines.get(i).productId(), cats)) { qty += lines.get(i).quantity(); value += sale[i] * lines.get(i).quantity(); }
        long pct = 0;
        if (o.config().get("tiers") instanceof List<?> tiers) for (Object t : tiers) if (t instanceof Map<?, ?> m && qty >= num(m, "minQty")) pct = Math.max(pct, num(m, "percent"));
        if (pct == 0) return 0;
        for (int i = 0; i < lines.size(); i++) if (covers(o, lines.get(i).productId(), cats)) split[i] += sale[i] * lines.get(i).quantity() * pct / 100;
        return value * pct / 100;
    }

    private static long threshold(Live o, long subtotal) {
        if (subtotal < num(o.config(), "minSubtotalMinor")) return 0;
        long v = num(o.config(), "value");
        long off = "PERCENT".equals(o.config().get("discountType")) ? subtotal * v / 100 : v;
        long cap = numOr(o.config(), "maxDiscountMinor", 0);
        return cap > 0 ? Math.min(off, cap) : off;
    }

    private long firstOrder(UUID tenantId, UUID customerId, Live o, long subtotal) {
        if (customerId != null) {
            long before = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND customer_id = :c AND status NOT IN ('CANCELLED')")
                    .param("t", tenantId).param("c", customerId).query(Long.class).single();
            if (before > 0) return 0;
        }
        long v = num(o.config(), "value");
        return "PERCENT".equals(o.config().get("discountType")) ? subtotal * v / 100 : v;
    }

    private static Map<String, Object> appliedRow(Live o, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("offerId", o.id().toString());
        m.put("name", o.name());
        m.put("type", o.type());
        m.put("amountMinor", amount);
        return m;
    }

    /** Called when the order is placed: counts the use and the money given. */
    void recordUse(UUID tenantId, List<Map<String, Object>> applied) {
        for (var a : applied)
            jdbc.sql("UPDATE commerce.offers SET usage_count = usage_count + 1, discount_given_minor = discount_given_minor + :d WHERE id = :i AND tenant_id = :t")
                    .param("d", ((Number) a.get("amountMinor")).longValue()).param("i", UUID.fromString((String) a.get("offerId"))).param("t", tenantId).update();
    }

    String appliedJson(List<Map<String, Object>> applied) { return write(applied); }

    // ---- storefront ----------------------------------------------------------------------------------------------

    /**
     * Adds the live sale to product cards: {@code offer = {badge, type, percent?, endsAt, salePriceMinor}} computed from the card's lowest price.
     * Cards need "id" and "minPriceMinor".
     */
    public void decorate(UUID tenantId, List<Map<String, Object>> cards) {
        if (cards == null || cards.isEmpty()) return;
        List<Live> offers = live(tenantId).stream().filter(o -> ITEM_TYPES.contains(o.type()) || "BUY_X_GET_Y".equals(o.type()) || "TIERED_QTY".equals(o.type()) || "BUNDLE_PRICE".equals(o.type())).toList();
        if (offers.isEmpty()) return;
        List<UUID> ids = new ArrayList<>();
        for (var c : cards) if (c.get("id") instanceof UUID u) ids.add(u); else if (c.get("id") instanceof String s) try { ids.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        var cats = categoriesOf(tenantId, ids);
        for (var c : cards) {
            UUID id = c.get("id") instanceof UUID u ? u : c.get("id") instanceof String s ? safeUuid(s) : null;
            if (id == null) continue;
            long price = c.get("minPriceMinor") instanceof Number n ? n.longValue() : 0;
            Map<String, Object> o = cardOffer(offers, id, price, cats);
            if (o != null) c.put("offer", o);
        }
    }

    /** The product page: the card offer plus a sale price per variant. */
    public void decorateProduct(UUID tenantId, Map<String, Object> product) {
        if (!(product.get("id") instanceof UUID id)) return;
        List<Live> offers = live(tenantId);
        if (offers.isEmpty()) return;
        var cats = categoriesOf(tenantId, List.of(id));
        long min = Long.MAX_VALUE;
        if (product.get("variants") instanceof List<?> vs) for (Object v : vs) if (v instanceof Map<?, ?> vm && vm.get("priceMinor") instanceof Number n) min = Math.min(min, n.longValue());
        Map<String, Object> o = cardOffer(offers, id, min == Long.MAX_VALUE ? 0 : min, cats);
        if (o != null) product.put("offer", o);
        Live best = bestItemOffer(offers, id, cats, min == Long.MAX_VALUE ? 0 : min);
        if (best != null && product.get("variants") instanceof List<?> vs)
            for (Object v : vs) if (v instanceof Map<?, ?> vm && vm.get("priceMinor") instanceof Number n) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) vm;
                m.put("salePriceMinor", n.longValue() - unitOff(best, n.longValue()));
            }
        // the cart offers this product is part of, as hints ("buy 2 get 1 free")
        List<Map<String, Object>> hints = new ArrayList<>();
        for (Live x : offers) if (!ITEM_TYPES.contains(x.type()) && covers(x, id, cats)) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("type", x.type()); h.put("name", x.name()); h.put("badge", x.badge()); h.put("config", x.config());
            if (x.endsAt() != null) h.put("endsAt", x.endsAt());
            hints.add(h);
        }
        if (!hints.isEmpty()) product.put("offerHints", hints);
    }

    private static Live bestItemOffer(List<Live> offers, UUID id, Map<UUID, Set<Integer>> cats, long price) {
        Live best = null;
        long bestOff = 0;
        for (Live o : offers) if (ITEM_TYPES.contains(o.type()) && covers(o, id, cats)) {
            long off = unitOff(o, Math.max(price, 1));
            if (best == null || off > bestOff) { best = o; bestOff = off; }
        }
        return best;
    }

    private static Map<String, Object> cardOffer(List<Live> offers, UUID id, long price, Map<UUID, Set<Integer>> cats) {
        Live best = bestItemOffer(offers, id, cats, price);
        Map<String, Object> m = new LinkedHashMap<>();
        if (best != null) {
            m.put("offerId", best.id().toString());
            m.put("type", best.type());
            m.put("badge", best.badge().isBlank() ? null : best.badge());
            if (best.config().get("percent") != null) m.put("percent", best.config().get("percent"));
            m.put("salePriceMinor", price - unitOff(best, price));
            if (best.endsAt() != null) m.put("endsAt", best.endsAt());
            return m;
        }
        for (Live o : offers) if (!ITEM_TYPES.contains(o.type()) && covers(o, id, cats)) {
            m.put("offerId", o.id().toString());
            m.put("type", o.type());
            m.put("badge", o.badge().isBlank() ? null : o.badge());
            m.put("config", o.config());
            if (o.endsAt() != null) m.put("endsAt", o.endsAt());
            return m;
        }
        return null;
    }

    /** Running offers the shop wants announced (flash sale countdowns, free delivery, spend-and-save...). */
    public List<Map<String, Object>> banners(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        var rows = jdbc.sql("""
                SELECT id, name, type, badge_text, description, banner_image, ends_at, config::text AS config_text FROM commerce.offers
                WHERE tenant_id = :t AND is_active AND NOT is_draft AND (starts_at IS NULL OR starts_at <= now()) AND (ends_at IS NULL OR ends_at > now())
                AND (max_uses IS NULL OR usage_count < max_uses) AND (show_banner OR type = 'FLASH_SALE') ORDER BY priority DESC, ends_at NULLS LAST LIMIT 5
                """).param("t", tenantId).query().listOfRows();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id")); m.put("name", r.get("name")); m.put("type", r.get("type")); m.put("badge", r.get("badge_text"));
            m.put("description", r.get("description")); m.put("image", r.get("banner_image")); m.put("endsAt", instant(r.get("ends_at")));
            m.put("config", Rows.json(r.get("config_text")));
            out.add(m);
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static UUID safeUuid(String s) { try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; } }

    private static Long num(Map<?, ?> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) try { return Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return null; }
        return null;
    }

    private static long numOr(Map<?, ?> m, String k, long d) { Long v = num(m, k); return v == null ? d : v; }

    private static Instant instant(Object o) {
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime t) return t.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private static java.sql.Timestamp ts(Instant i) { return i == null ? null : java.sql.Timestamp.from(i); }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private String write(Object o) {
        try { return json.writeValueAsString(o); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
