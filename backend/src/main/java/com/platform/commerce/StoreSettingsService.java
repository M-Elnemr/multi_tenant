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
        Map<String, Object> p = new java.util.LinkedHashMap<>(Rows.camel(jdbc.sql("SELECT store_name, short_description, about, support_phone, support_email, address_text, shipping_policy, return_policy, privacy_policy, terms_text, other_category FROM commerce.store_profiles WHERE tenant_id = :t")
                .param("t", tenantId).query().singleRow()));
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

    @Transactional
    public Map<String, Object> updateProfile(UUID tenantId, UUID actor, Map<String, String> f) {
        jdbc.sql("""
                UPDATE commerce.store_profiles SET store_name = coalesce(:storeName, store_name), short_description = coalesce(:shortDescription, short_description),
                  about = coalesce(:about, about), support_phone = coalesce(:supportPhone, support_phone), support_email = coalesce(:supportEmail, support_email),
                  address_text = coalesce(:addressText, address_text), shipping_policy = coalesce(:shippingPolicy, shipping_policy),
                  return_policy = coalesce(:returnPolicy, return_policy), privacy_policy = coalesce(:privacyPolicy, privacy_policy),
                  terms_text = coalesce(:termsText, terms_text), updated_at = now() WHERE tenant_id = :t
                """).param("t", tenantId).param("storeName", f.get("storeName")).param("shortDescription", f.get("shortDescription")).param("about", f.get("about"))
                .param("supportPhone", f.get("supportPhone")).param("supportEmail", f.get("supportEmail")).param("addressText", f.get("addressText"))
                .param("shippingPolicy", f.get("shippingPolicy")).param("returnPolicy", f.get("returnPolicy")).param("privacyPolicy", f.get("privacyPolicy"))
                .param("termsText", f.get("termsText")).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "store_profile", null, null);
        return profile(tenantId);
    }

    // ---- branches -----------------------------------------------------------------------------------------

    public List<Map<String, Object>> branches(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, name, code, phone, address_line1, address_line2, city, state, country, district, is_active FROM commerce.branches WHERE tenant_id = :t ORDER BY created_at")
                .param("t", tenantId).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> createBranch(UUID tenantId, UUID actor, String name, String code, String phone, String address, String city) {
        if (name == null || name.isBlank() || code == null || code.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name and code are required");
        ent.requireCapacity(tenantId, "max_branches", jdbc.sql("SELECT count(*) FROM commerce.branches WHERE tenant_id = :t AND is_active").param("t", tenantId).query(Long.class).single());
        try {
            UUID id = jdbc.sql("INSERT INTO commerce.branches (tenant_id, name, code, phone, address_line1, city) VALUES (:t, :n, :c, :p, coalesce(:a,''), coalesce(:ci,'')) RETURNING id")
                    .param("t", tenantId).param("n", name.trim()).param("c", code.trim().toUpperCase()).param("p", phone).param("a", address).param("ci", city).query(UUID.class).single();
            audit.record(actor, tenantId, "BRANCH_CREATED", "branch", id, null);
            return branches(tenantId).stream().filter(b -> id.equals(b.get("id"))).findFirst().orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("BRANCH_CODE_TAKEN", "Branch code already exists");
        }
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

    public Map<String, Object> customers(UUID tenantId, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.customers WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT c.id, c.customer_number, u.first_name, u.last_name, u.phone, c.created_at,
                  (SELECT count(*) FROM commerce.orders o WHERE o.tenant_id = c.tenant_id AND o.user_id = c.user_id) AS orders_count,
                  (SELECT coalesce(sum(o.total_minor),0) FROM commerce.orders o WHERE o.tenant_id = c.tenant_id AND o.user_id = c.user_id AND o.payment_status = 'PAID') AS total_spent_minor
                FROM commerce.customers c JOIN core.users u ON u.id = c.user_id WHERE c.tenant_id = :t ORDER BY c.created_at DESC LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }
}
