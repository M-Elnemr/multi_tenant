package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Store profile, branches, payment methods, shipping, coupons, review moderation, customer list. */
@Service
public class StoreSettingsService {
    private final JdbcClient jdbc;
    private final EntitlementService ent;
    private final AuditService audit;
    private final com.platform.shared.PaymentPolicy policy;

    public StoreSettingsService(JdbcClient jdbc, EntitlementService ent, AuditService audit, com.platform.shared.PaymentPolicy policy) {
        this.policy = policy;
        this.jdbc = jdbc;
        this.ent = ent;
        this.audit = audit;
    }

    // ---- profile ------------------------------------------------------------------------------------------

    public Map<String, Object> profile(UUID tenantId) {
        Map<String, Object> p = new java.util.LinkedHashMap<>(Rows.camel(jdbc.sql("SELECT store_name, short_description, about, support_phone, support_email, address_text, shipping_policy, return_policy, privacy_policy, terms_text, other_category, "
                + "whatsapp, extra_phones::text AS extra_phones, facebook_url, instagram_url, tiktok_url, website_url, maps_url, working_hours::text AS working_hours, "
                + "is_open, closed_message, min_order_minor, tax_id, vat_included, vat_percent, cover_file_id, announcement, return_window_days "
                + "FROM commerce.store_profiles WHERE tenant_id = :t")
                .param("t", tenantId).query().singleRow()));
        p.put("extraPhones", Rows.jsonList(p.get("extraPhones")));
        p.put("workingHours", Rows.json(p.get("workingHours")));
        p.put("categories", Rows.camel(jdbc.sql("SELECT c.code, c.name_ar, c.name_en FROM commerce.store_category_links l JOIN commerce.store_categories c ON c.id = l.category_id WHERE l.tenant_id = :t ORDER BY c.sort_order").param("t", tenantId).query().listOfRows()));
        return p;
    }

    public List<Map<String, Object>> categories() { return listCategories(jdbc); }

    /** Most common first, then A-Z; "other" is last. */
    static List<Map<String, Object>> listCategories(JdbcClient jdbc) {
        return Rows.camel(jdbc.sql("SELECT code, name_ar, name_en, popular FROM commerce.store_categories WHERE is_active ORDER BY sort_order, name_en").query().listOfRows());
    }

    /** Replaces the shop's categories. "other" requires the typed name, kept only while "other" is chosen. */
    static void setCategories(JdbcClient jdbc, UUID tenantId, List<String> codes, String other) {
        jdbc.sql("DELETE FROM commerce.store_category_links WHERE tenant_id = :t").param("t", tenantId).update();
        for (String code : codes) {
            int n = jdbc.sql("INSERT INTO commerce.store_category_links (tenant_id, category_id) SELECT :t, id FROM commerce.store_categories WHERE code = :c AND is_active").param("t", tenantId).param("c", code).update();
            if (n == 0) throw BusinessException.badRequest("UNKNOWN_CATEGORY", "Unknown category " + code);
        }
        boolean hasOther = codes.contains("other");
        if (hasOther && (other == null || other.trim().length() < 2)) throw BusinessException.badRequest("OTHER_CATEGORY_REQUIRED", "Please type the name for \"Other\"");
        jdbc.sql("UPDATE commerce.store_profiles SET other_category = :o, updated_at = now() WHERE tenant_id = :t").param("o", hasOther ? other.trim() : null).param("t", tenantId).update();
    }

    @Transactional
    public Map<String, Object> updateCategories(UUID tenantId, UUID actor, List<String> codes, String other) {
        if (codes == null || codes.isEmpty()) throw BusinessException.badRequest("CATEGORY_REQUIRED", "Choose at least one category");
        if (codes.size() > 5) throw BusinessException.badRequest("TOO_MANY_CATEGORIES", "Choose up to 5");
        setCategories(jdbc, tenantId, codes.stream().map(String::trim).map(String::toLowerCase).distinct().toList(), other);
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "store_profile", null, null);
        return profile(tenantId);
    }

    private static final Map<String, String> PROFILE_TEXT = Map.ofEntries(
            Map.entry("storeName", "store_name"), Map.entry("shortDescription", "short_description"), Map.entry("about", "about"),
            Map.entry("supportPhone", "support_phone"), Map.entry("supportEmail", "support_email"), Map.entry("addressText", "address_text"),
            Map.entry("shippingPolicy", "shipping_policy"), Map.entry("returnPolicy", "return_policy"), Map.entry("privacyPolicy", "privacy_policy"),
            Map.entry("termsText", "terms_text"), Map.entry("whatsapp", "whatsapp"), Map.entry("facebookUrl", "facebook_url"),
            Map.entry("instagramUrl", "instagram_url"), Map.entry("tiktokUrl", "tiktok_url"), Map.entry("websiteUrl", "website_url"),
            Map.entry("mapsUrl", "maps_url"), Map.entry("closedMessage", "closed_message"), Map.entry("taxId", "tax_id"), Map.entry("announcement", "announcement"));
    private static final Map<String, String> PROFILE_BOOL = Map.of("isOpen", "is_open", "vatIncluded", "vat_included");
    private static final Map<String, String> PROFILE_NUM = Map.of("minOrderMinor", "min_order_minor", "vatPercent", "vat_percent", "returnWindowDays", "return_window_days");

    /** Edits only the fields that are present in the request (whitelisted columns; values are always bound, never concatenated). */
    @Transactional
    public Map<String, Object> updateProfile(UUID tenantId, UUID actor, Map<String, Object> f) {
        StringBuilder set = new StringBuilder();
        var q = new java.util.LinkedHashMap<String, Object>();
        for (var e : PROFILE_TEXT.entrySet()) {
            if (!(f.get(e.getKey()) instanceof String v)) continue;
            String val = v.trim();
            if (e.getKey().equals("storeName") && val.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Store name is required");
            if (val.length() > 5000 || (e.getKey().endsWith("Url") && !val.isEmpty() && !val.matches("(?i)^https?://\\S+$"))) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid " + e.getKey());
            set.append(e.getValue()).append(" = :").append(e.getKey()).append(", ");
            q.put(e.getKey(), val);
        }
        for (var e : PROFILE_BOOL.entrySet()) if (f.get(e.getKey()) instanceof Boolean v) { set.append(e.getValue()).append(" = :").append(e.getKey()).append(", "); q.put(e.getKey(), v); }
        for (var e : PROFILE_NUM.entrySet()) if (f.get(e.getKey()) instanceof Number v) {
            if (v.doubleValue() < 0 || v.doubleValue() > 1_000_000_000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid " + e.getKey());
            set.append(e.getValue()).append(" = :").append(e.getKey()).append(", ");
            q.put(e.getKey(), e.getKey().equals("vatPercent") ? (Object) java.math.BigDecimal.valueOf(v.doubleValue()) : (Object) v.longValue());
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            if (f.get("extraPhones") instanceof List<?> l) {
                List<String> phones = l.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).limit(5).toList();
                set.append("extra_phones = CAST(:extraPhones AS jsonb), "); q.put("extraPhones", json.writeValueAsString(phones));
            }
            if (f.get("workingHours") instanceof Map<?, ?> m) {
                String wh = json.writeValueAsString(m);
                if (wh.length() > 2000) throw BusinessException.badRequest("VALIDATION_ERROR", "Working hours too long");
                set.append("working_hours = CAST(:workingHours AS jsonb), "); q.put("workingHours", wh);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid value"); }
        if (f.containsKey("coverFileId")) {
            Object c = f.get("coverFileId");
            set.append("cover_file_id = :coverFileId, "); q.put("coverFileId", c == null || String.valueOf(c).isBlank() ? null : UUID.fromString(String.valueOf(c)));
        }
        if (!q.isEmpty()) {
            var st = jdbc.sql("UPDATE commerce.store_profiles SET " + set + "updated_at = now() WHERE tenant_id = :t").param("t", tenantId);
            for (var e : q.entrySet()) st = st.param(e.getKey(), e.getValue());
            st.update();
        }
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "store_profile", null, null);
        return profile(tenantId);
    }

    /** What the storefront needs to look like the shop itself: identity, contact, hours, look & feel (public, no internal fields). */
    public Map<String, Object> publicProfile(UUID tenantId) {
        Map<String, Object> p = new java.util.LinkedHashMap<>(profile(tenantId));
        p.remove("taxId"); p.remove("otherCategory");
        var b = jdbc.sql("SELECT logo_file_id, primary_color, secondary_color FROM core.branding WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst();
        p.put("branding", b.isPresent() ? Rows.camel(b.get()) : Map.of());
        return p;
    }

    // ---- branches -----------------------------------------------------------------------------------------

    private static final String BRANCH_COLS = "id, name, code, phone, whatsapp, address_line1, address_line2, city, state, country, district, governorate_code, area, landmark, "
            + "working_hours::text AS working_hours, maps_url, latitude, longitude, is_pickup, sort_order, is_active";

    public List<Map<String, Object>> branches(UUID tenantId) { return branches(tenantId, false); }

    public List<Map<String, Object>> branches(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("SELECT " + BRANCH_COLS + " FROM commerce.branches WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY sort_order, created_at")
                .param("t", tenantId).param("a", onlyActive).query().listOfRows()).stream().map(m -> { m.put("workingHours", Rows.json(m.remove("workingHours"))); return m; }).toList();
    }

    private static final Map<String, String> BRANCH_TEXT = Map.ofEntries(
            Map.entry("name", "name"), Map.entry("phone", "phone"), Map.entry("whatsapp", "whatsapp"), Map.entry("address", "address_line1"), Map.entry("addressLine2", "address_line2"),
            Map.entry("city", "city"), Map.entry("district", "district"), Map.entry("governorateCode", "governorate_code"), Map.entry("area", "area"),
            Map.entry("landmark", "landmark"), Map.entry("mapsUrl", "maps_url"));

    @Transactional
    public Map<String, Object> createBranch(UUID tenantId, UUID actor, Map<String, Object> f) {
        String name = f.get("name") instanceof String s ? s.trim() : "", code = f.get("code") instanceof String s ? s.trim() : "";
        if (name.isEmpty() || code.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name and code are required");
        ent.requireCapacity(tenantId, "max_branches", jdbc.sql("SELECT count(*) FROM commerce.branches WHERE tenant_id = :t AND is_active").param("t", tenantId).query(Long.class).single());
        try {
            UUID id = jdbc.sql("INSERT INTO commerce.branches (tenant_id, name, code) VALUES (:t, :n, :c) RETURNING id").param("t", tenantId).param("n", name).param("c", code.toUpperCase()).query(UUID.class).single();
            applyBranch(tenantId, id, f);
            audit.record(actor, tenantId, "BRANCH_CREATED", "branch", id, null);
            return branches(tenantId).stream().filter(b -> id.equals(b.get("id"))).findFirst().orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("BRANCH_CODE_TAKEN", "Branch code already exists");
        }
    }

    @Transactional
    public Map<String, Object> updateBranch(UUID tenantId, UUID actor, UUID id, Map<String, Object> f) {
        if (jdbc.sql("SELECT count(*) FROM commerce.branches WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query(Long.class).single() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        applyBranch(tenantId, id, f);
        audit.record(actor, tenantId, "BRANCH_UPDATED", "branch", id, null);
        return branches(tenantId).stream().filter(b -> id.equals(b.get("id"))).findFirst().orElseThrow();
    }

    private void applyBranch(UUID tenantId, UUID id, Map<String, Object> f) {
        StringBuilder set = new StringBuilder();
        var q = new java.util.LinkedHashMap<String, Object>();
        for (var e : BRANCH_TEXT.entrySet()) if (f.get(e.getKey()) instanceof String v) {
            String val = v.trim();
            if (e.getKey().equals("name") && val.isEmpty()) continue;
            if (val.length() > 500) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid " + e.getKey());
            set.append(e.getValue()).append(" = :").append(e.getKey()).append(", "); q.put(e.getKey(), val);
        }
        if (f.get("isPickup") instanceof Boolean b) { set.append("is_pickup = :isPickup, "); q.put("isPickup", b); }
        if (f.get("isActive") instanceof Boolean b) { set.append("is_active = :isActive, "); q.put("isActive", b); }
        if (f.get("sortOrder") instanceof Number n) { set.append("sort_order = :sortOrder, "); q.put("sortOrder", n.intValue()); }
        if (f.get("latitude") instanceof Number n) { set.append("latitude = :latitude, "); q.put("latitude", java.math.BigDecimal.valueOf(n.doubleValue())); }
        if (f.get("longitude") instanceof Number n) { set.append("longitude = :longitude, "); q.put("longitude", java.math.BigDecimal.valueOf(n.doubleValue())); }
        if (f.get("workingHours") instanceof Map<?, ?> m) {
            try { set.append("working_hours = CAST(:workingHours AS jsonb), "); q.put("workingHours", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid working hours"); }
        }
        if (q.isEmpty()) return;
        var st = jdbc.sql("UPDATE commerce.branches SET " + set + "updated_at = now() WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId);
        for (var e : q.entrySet()) st = st.param(e.getKey(), e.getValue());
        st.update();
    }

    // ---- payment methods & shipping --------------------------------------------------------------------------

    public List<Map<String, Object>> paymentMethods(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT method, enabled FROM commerce.payment_method_settings WHERE tenant_id = :t AND (:card OR method <> 'CARD') ORDER BY method")
                .param("t", tenantId).param("card", policy.cardEnabled()).query().listOfRows());
    }

    @Transactional
    public List<Map<String, Object>> setPaymentMethod(UUID tenantId, UUID actor, String method, boolean enabled) {
        if (!Set.of("CARD", "CASH_ON_DELIVERY").contains(method) || ("CARD".equals(method) && enabled && !policy.cardEnabled())) throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "Online card payment is not available yet");
        jdbc.sql("INSERT INTO commerce.payment_method_settings (tenant_id, method, enabled) VALUES (:t, :m, :e) ON CONFLICT (tenant_id, method) DO UPDATE SET enabled = :e")
                .param("t", tenantId).param("m", method).param("e", enabled).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "payment_method", null, "{\"method\":\"" + method + "\",\"enabled\":" + enabled + "}");
        return paymentMethods(tenantId);
    }

    public List<Map<String, Object>> shippingMethods(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("SELECT id, type, name, fee_minor, free_above_minor, is_active FROM commerce.shipping_methods WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY sort_order, name")
                .param("t", tenantId).param("a", onlyActive).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> createShipping(UUID tenantId, UUID actor, String type, String name, long feeMinor, Long freeAboveMinor) {
        if (!Set.of("PICKUP", "FIXED", "FREE_ABOVE").contains(type) || name == null || name.isBlank() || feeMinor < 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid shipping method");
        if ("FREE_ABOVE".equals(type) && freeAboveMinor == null) throw BusinessException.badRequest("VALIDATION_ERROR", "freeAboveMinor is required");
        UUID id = jdbc.sql("INSERT INTO commerce.shipping_methods (tenant_id, type, name, fee_minor, free_above_minor) VALUES (:t, :ty, :n, :f, :fa) RETURNING id")
                .param("t", tenantId).param("ty", type).param("n", name.trim()).param("f", "PICKUP".equals(type) ? 0 : feeMinor).param("fa", freeAboveMinor).query(UUID.class).single();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "shipping_method", id, null);
        return shippingMethods(tenantId, false).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public void setShippingActive(UUID tenantId, UUID id, boolean active) {
        if (jdbc.sql("UPDATE commerce.shipping_methods SET is_active = :a WHERE id = :i AND tenant_id = :t").param("a", active).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    @Transactional
    public Map<String, Object> updateShipping(UUID tenantId, UUID actor, UUID id, String name, Long feeMinor, Long freeAboveMinor) {
        if (feeMinor != null && feeMinor < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid fee");
        if (jdbc.sql("UPDATE commerce.shipping_methods SET name = coalesce(:n, name), fee_minor = CASE WHEN type = 'PICKUP' THEN 0 ELSE coalesce(:f, fee_minor) END, free_above_minor = coalesce(:fa, free_above_minor) WHERE id = :i AND tenant_id = :t")
                .param("n", name == null || name.isBlank() ? null : name.trim()).param("f", feeMinor).param("fa", freeAboveMinor).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "shipping_method", id, null);
        return shippingMethods(tenantId, false).stream().filter(s -> id.equals(s.get("id"))).findFirst().orElseThrow();
    }

    /** Orders keep their own snapshot, so an unused method can be removed; one that orders reference is only switched off. */
    @Transactional
    public Map<String, Object> deleteShipping(UUID tenantId, UUID actor, UUID id) {
        boolean used = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE shipping_method_id = :i").param("i", id).query(Long.class).single() > 0;
        int n = used ? jdbc.sql("UPDATE commerce.shipping_methods SET is_active = FALSE WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update()
                : jdbc.sql("DELETE FROM commerce.shipping_methods WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "shipping_method", id, null);
        return Map.of("ok", true, "deleted", !used);
    }

    // ---- coupons --------------------------------------------------------------------------------------------------

    public record CouponReq(String code, String discountType, long value, Long minOrderMinor, Instant startsAt, Instant endsAt, Integer maxRedemptions, Integer perCustomerLimit) {}

    @Transactional
    public Map<String, Object> createCoupon(UUID tenantId, UUID actor, CouponReq r) {
        ent.requireFeature(tenantId, "coupons");
        if (r.code() == null || r.code().isBlank() || !Set.of("PERCENT", "FIXED").contains(r.discountType()) || r.value() <= 0
                || ("PERCENT".equals(r.discountType()) && r.value() > 100)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid coupon");
        try {
            UUID id = jdbc.sql("""
                    INSERT INTO commerce.coupons (tenant_id, code, discount_type, value, min_order_minor, starts_at, ends_at, max_redemptions, per_customer_limit)
                    VALUES (:t, upper(:c), :dt, :v, :m, :s, :e, :mr, :pc) RETURNING id
                    """).param("t", tenantId).param("c", r.code().trim()).param("dt", r.discountType()).param("v", r.value()).param("m", r.minOrderMinor() == null ? 0 : r.minOrderMinor())
                    .param("s", r.startsAt() == null ? null : java.sql.Timestamp.from(r.startsAt())).param("e", r.endsAt() == null ? null : java.sql.Timestamp.from(r.endsAt()))
                    .param("mr", r.maxRedemptions()).param("pc", r.perCustomerLimit()).query(UUID.class).single();
            audit.record(actor, tenantId, "COUPON_CREATED", "coupon", id, null);
            return coupons(tenantId).stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("COUPON_CODE_TAKEN", "Coupon code already exists");
        }
    }

    @Transactional
    public Map<String, Object> setCouponActive(UUID tenantId, UUID actor, UUID id, boolean active) {
        if (jdbc.sql("UPDATE commerce.coupons SET is_active = :a WHERE id = :i AND tenant_id = :t").param("a", active).param("i", id).param("t", tenantId).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "COUPON_UPDATED", "coupon", id, null);
        return coupons(tenantId).stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> deleteCoupon(UUID tenantId, UUID actor, UUID id) {
        boolean used = jdbc.sql("SELECT count(*) FROM commerce.coupon_redemptions WHERE coupon_id = :i").param("i", id).query(Long.class).single() > 0;
        int n = used ? jdbc.sql("UPDATE commerce.coupons SET is_active = FALSE WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update()
                : jdbc.sql("DELETE FROM commerce.coupons WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "COUPON_UPDATED", "coupon", id, null);
        return Map.of("ok", true, "deleted", !used);
    }

    public List<Map<String, Object>> coupons(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, code, discount_type, value, min_order_minor, starts_at, ends_at, max_redemptions, per_customer_limit, is_active, redemptions FROM commerce.coupons WHERE tenant_id = :t ORDER BY created_at DESC")
                .param("t", tenantId).query().listOfRows());
    }

    // ---- reviews / customers -----------------------------------------------------------------------------------------

    public Map<String, Object> reviewsForModeration(UUID tenantId, Page page, String status) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.reviews WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))").param("t", tenantId).param("s", status).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT r.id, r.rating, r.review_text, r.status, r.created_at, p.name AS product_name FROM commerce.reviews r JOIN commerce.products p ON p.id = r.product_id
                WHERE r.tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR r.status = CAST(:s AS varchar)) ORDER BY r.created_at DESC LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("s", status).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    @Transactional
    public void moderateReview(UUID tenantId, UUID actor, UUID id, String status) {
        if (!Set.of("APPROVED", "REJECTED", "HIDDEN").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");
        if (jdbc.sql("UPDATE commerce.reviews SET status = :s, updated_at = now() WHERE id = :i AND tenant_id = :t").param("s", status).param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        audit.record(actor, tenantId, "REVIEW_MODERATED", "review", id, "{\"status\":\"" + status + "\"}");
    }

    /** The store's clients (guests and signed-in Google clients), read-only: a store cannot create or edit them. */
    public Map<String, Object> customers(UUID tenantId, Page page, String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = "c.tenant_id = :t AND (CAST(:q AS varchar) IS NULL OR c.name ILIKE CAST(:q AS varchar) OR c.phone LIKE CAST(:q AS varchar) OR c.customer_number ILIKE CAST(:q AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM commerce.customers c WHERE " + where).param("t", tenantId).param("q", like).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT c.id, c.customer_number, c.name, c.phone, c.email, c.address_json::text AS address, (c.user_id IS NOT NULL) AS has_account, c.created_at,
                  (SELECT count(*) FROM commerce.orders o WHERE o.customer_id = c.id) AS orders_count,
                  (SELECT coalesce(sum(o.total_minor),0) FROM commerce.orders o WHERE o.customer_id = c.id AND o.status NOT IN ('CANCELLED','RETURNED','REFUNDED')) AS total_spent_minor,
                  (SELECT max(o.created_at) FROM commerce.orders o WHERE o.customer_id = c.id) AS last_order_at
                FROM commerce.customers c WHERE\s""" + where + """
                 ORDER BY c.created_at DESC LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("q", like).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        var data = Rows.camel(rows).stream().map(m -> { m.put("address", Rows.json(m.get("address"))); return m; }).toList();
        return page.wrap(data, total);
    }

    public java.util.List<Map<String, Object>> customerOrders(UUID tenantId, UUID customerId) {
        return Rows.camel(jdbc.sql("SELECT id, order_number, status, payment_status, total_minor, currency, created_at FROM commerce.orders WHERE tenant_id = :t AND customer_id = :c ORDER BY created_at DESC LIMIT 50")
                .param("t", tenantId).param("c", customerId).query().listOfRows());
    }
}
