package com.platform.commerce;

import com.platform.billing.EntitlementService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Things a logged-in shopper does on a store besides checkout: wishlist, reviews, saved addresses. */
@Service
public class ShopperService {
    private final JdbcClient jdbc;
    private final EntitlementService ent;
    private final StoreCustomerService customers;

    public ShopperService(JdbcClient jdbc, EntitlementService ent, StoreCustomerService customers) {
        this.jdbc = jdbc;
        this.ent = ent;
        this.customers = customers;
    }

    @Transactional
    public void wishlistAdd(UUID tenantId, UUID userId, UUID productId) {
        if (jdbc.sql("SELECT count(*) FROM commerce.products WHERE id = :p AND tenant_id = :t AND status = 'ACTIVE'").param("p", productId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found");
        jdbc.sql("INSERT INTO commerce.wishlist_items (tenant_id, user_id, product_id) VALUES (:t, :u, :p) ON CONFLICT DO NOTHING").param("t", tenantId).param("u", userId).param("p", productId).update();
    }

    @Transactional
    public void wishlistRemove(UUID tenantId, UUID userId, UUID productId) {
        jdbc.sql("DELETE FROM commerce.wishlist_items WHERE tenant_id = :t AND user_id = :u AND product_id = :p").param("t", tenantId).param("u", userId).param("p", productId).update();
    }

    public List<Map<String, Object>> wishlist(UUID tenantId, UUID userId) {
        return Rows.camel(jdbc.sql("SELECT p.id, p.name, p.slug FROM commerce.wishlist_items w JOIN commerce.products p ON p.id = w.product_id WHERE w.tenant_id = :t AND w.user_id = :u AND p.status = 'ACTIVE' ORDER BY w.created_at DESC")
                .param("t", tenantId).param("u", userId).query().listOfRows());
    }

    /** Only shoppers who received the product may review it; reviews wait for merchant moderation. */
    @Transactional
    public void review(UUID tenantId, UUID userId, UUID productId, int rating, String text) {
        ent.requireFeature(tenantId, "reviews");
        if (rating < 1 || rating > 5) throw BusinessException.badRequest("VALIDATION_ERROR", "Rating must be 1-5");
        boolean bought = jdbc.sql("""
                SELECT count(*) FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id
                WHERE o.tenant_id = :t AND o.user_id = :u AND i.product_id = :p AND o.status = 'ARRIVED'
                """).param("t", tenantId).param("u", userId).param("p", productId).query(Long.class).single() > 0;
        if (!bought) throw BusinessException.forbidden("REVIEW_NOT_ALLOWED", "Only customers who received this product can review it");
        UUID customerId = customers.ensureCustomer(tenantId, userId);
        try {
            jdbc.sql("INSERT INTO commerce.reviews (tenant_id, product_id, customer_id, rating, review_text) VALUES (:t, :p, :c, :r, :x)")
                    .param("t", tenantId).param("p", productId).param("c", customerId).param("r", rating).param("x", text).update();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("REVIEW_EXISTS", "You already reviewed this product");
        }
    }

    public Map<String, Object> profile(UUID clientId) {
        return Rows.camel(jdbc.sql("SELECT id, name, phone, email FROM commerce.client_accounts WHERE id = :u").param("u", clientId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Account not found")));
    }

    @Transactional
    public Map<String, Object> updateProfile(UUID clientId, String name, String phone) {
        String p = phone == null || phone.isBlank() ? null : com.platform.shared.PhoneNormalizer.normalize(phone);
        jdbc.sql("UPDATE commerce.client_accounts SET name = coalesce(nullif(btrim(:n), ''), name), phone = coalesce(:p, phone) WHERE id = :u").param("n", name).param("p", p).param("u", clientId).update();
        return profile(clientId);
    }

    /**
     * The client deletes their own account: login identity, contact details, saved addresses, wishlist, push devices and sessions
     * are removed. Orders stay with the shops (they carry their own name/phone snapshot, needed for their accounting and returns).
     */
    @Transactional
    public void deleteAccount(UUID clientId) {
        jdbc.sql("UPDATE core.user_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", clientId).update();
        jdbc.sql("DELETE FROM notifications.device_tokens WHERE principal_id = :u").param("u", clientId).update();
        jdbc.sql("DELETE FROM commerce.addresses WHERE user_id = :u").param("u", clientId).update();
        jdbc.sql("DELETE FROM commerce.wishlist_items WHERE user_id = :u").param("u", clientId).update();
        jdbc.sql("UPDATE commerce.customers SET email = NULL, address_json = NULL, name = 'Deleted client' WHERE user_id = :u").param("u", clientId).update();
        jdbc.sql("""
                UPDATE commerce.client_accounts SET google_sub = 'deleted:' || id::text, email = NULL, name = '', phone = NULL,
                       status = 'DELETED', deleted_at = now() WHERE id = :u
                """).param("u", clientId).update();
    }

    public Map<String, Object> publicReviews(UUID tenantId, UUID productId) {
        var list = Rows.camel(jdbc.sql("""
                SELECT r.rating, r.review_text, r.created_at, split_part(c.name, ' ', 1) AS first_name FROM commerce.reviews r JOIN commerce.customers c ON c.id = r.customer_id
                WHERE r.tenant_id = :t AND r.product_id = :p AND r.status = 'APPROVED' ORDER BY r.created_at DESC LIMIT 50
                """).param("t", tenantId).param("p", productId).query().listOfRows());
        var agg = jdbc.sql("SELECT coalesce(round(avg(rating)::numeric, 2), 0) AS avg, count(*) AS cnt FROM commerce.reviews WHERE tenant_id = :t AND product_id = :p AND status = 'APPROVED'")
                .param("t", tenantId).param("p", productId).query().singleRow();
        return Map.of("average", agg.get("avg"), "count", agg.get("cnt"), "reviews", list);
    }

    public List<Map<String, Object>> addresses(UUID userId) {
        return Rows.camel(jdbc.sql("SELECT id, title, recipient_name, phone, address_line1, address_line2, city, state, district, country, postal_code, is_default_shipping FROM commerce.addresses WHERE user_id = :u ORDER BY created_at DESC")
                .param("u", userId).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> addAddress(UUID userId, CheckoutService.AddressReq a, String title, boolean makeDefault) {
        if (a.recipientName() == null || a.phone() == null || a.addressLine1() == null || a.city() == null)
            throw BusinessException.badRequest("VALIDATION_ERROR", "recipientName, phone, addressLine1 and city are required");
        if (makeDefault) jdbc.sql("UPDATE commerce.addresses SET is_default_shipping = FALSE WHERE user_id = :u").param("u", userId).update();
        UUID id = jdbc.sql("INSERT INTO commerce.addresses (user_id, title, recipient_name, phone, address_line1, address_line2, city, state, district, postal_code, is_default_shipping) VALUES (:u,:ti,:rn,:ph,:a1,:a2,:ci,:st,:di,:pc,:df) RETURNING id")
                .param("u", userId).param("ti", title).param("rn", a.recipientName()).param("ph", a.phone()).param("a1", a.addressLine1()).param("a2", a.addressLine2())
                .param("ci", a.city()).param("st", a.state()).param("di", a.district()).param("pc", a.postalCode()).param("df", makeDefault).query(UUID.class).single();
        return addresses(userId).stream().filter(x -> id.equals(x.get("id"))).findFirst().orElseThrow();
    }
}
