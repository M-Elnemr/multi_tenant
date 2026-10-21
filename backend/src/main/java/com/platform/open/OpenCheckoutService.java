package com.platform.open;

import com.platform.commerce.CheckoutService;
import com.platform.commerce.StoreCustomerService;
import com.platform.commerce.StoreFulfillmentService;
import com.platform.commerce.StoreSettingsService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Checkout across shops. One marketplace cart is split by shop and each part is placed as an ordinary order in that shop (its own prices, stock,
 * shipping, coupons and cash-on-delivery rules, and the shop's staff are notified exactly as for an order from the shop's own site).
 * The parts are tied together by an order group so the shopper sees one purchase.
 */
@Service
public class OpenCheckoutService {
    public record CartItem(UUID variantId, int quantity) {}
    /** coupons / shipping are keyed by shop id; both are optional. */
    public record CartReq(List<CartItem> items, Map<UUID, String> coupons, Map<UUID, UUID> shipping, CheckoutService.AddressReq address, String notes) {}

    private final JdbcClient jdbc;
    private final CheckoutService checkout;
    private final StoreSettingsService settings;
    private final StoreFulfillmentService fulfilment;
    private final StoreCustomerService customers;
    private final OpenUrls urls;

    public OpenCheckoutService(JdbcClient jdbc, CheckoutService checkout, StoreSettingsService settings, StoreFulfillmentService fulfilment, StoreCustomerService customers, OpenUrls urls) {
        this.jdbc = jdbc;
        this.checkout = checkout;
        this.settings = settings;
        this.fulfilment = fulfilment;
        this.customers = customers;
        this.urls = urls;
    }

    private record Shop(UUID id, String slug, String name, boolean eligible) {}

    /** The cart's lines grouped by shop (ordered by shop id so concurrent checkouts always lock in the same order). */
    private TreeMap<UUID, List<CheckoutService.Item>> byShop(List<CartItem> items) {
        if (items == null || items.isEmpty()) throw BusinessException.badRequest("CART_EMPTY", "Your cart is empty");
        if (items.size() > 60) throw BusinessException.badRequest("CART_TOO_LARGE", "Too many items");
        Map<UUID, UUID> shopOf = new LinkedHashMap<>();
        for (var r : jdbc.sql("SELECT id, tenant_id FROM commerce.product_variants WHERE id IN (:ids)").param("ids", items.stream().map(CartItem::variantId).toList()).query().listOfRows())
            shopOf.put((UUID) r.get("id"), (UUID) r.get("tenant_id"));
        TreeMap<UUID, List<CheckoutService.Item>> out = new TreeMap<>();
        for (CartItem i : items) {
            if (i.variantId() == null || i.quantity() < 1 || i.quantity() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid quantity");
            UUID shop = shopOf.get(i.variantId());
            if (shop == null) throw BusinessException.badRequest("PRODUCT_UNAVAILABLE", "An item in your cart is no longer available");
            out.computeIfAbsent(shop, k -> new ArrayList<>()).add(new CheckoutService.Item(i.variantId(), i.quantity()));
        }
        return out;
    }

    private Map<UUID, Shop> shops(java.util.Collection<UUID> ids) {
        Map<UUID, Shop> out = new LinkedHashMap<>();
        for (var r : jdbc.sql("""
                SELECT t.id, t.slug, t.name, (t.tenant_type = 'STORE' AND t.status IN ('TRIAL','ACTIVE','PAST_DUE') AND coalesce(sp.is_open, FALSE) AND coalesce(sp.open_listed, FALSE)) AS eligible
                FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id IN (:ids)
                """).param("ids", ids).query().listOfRows())
            out.put((UUID) r.get("id"), new Shop((UUID) r.get("id"), (String) r.get("slug"), (String) r.get("name"), Boolean.TRUE.equals(r.get("eligible"))));
        return out;
    }

    /** The shop's delivery method for this address: the shopper's pick when valid, else the first delivering one. Null when the shop cannot deliver there. */
    private Map<String, Object> pickShipping(UUID shop, String governorate, UUID preferred) {
        List<Map<String, Object>> methods = settings.shippingMethods(shop, true);
        if (preferred != null) {
            var chosen = methods.stream().filter(m -> preferred.equals(m.get("id"))).findFirst();
            if (chosen.isPresent()) return chosen.get();
        }
        for (var m : methods) {
            String type = (String) m.get("type");
            if ("PICKUP".equals(type)) continue;
            if ("ZONES".equals(type) && fulfilment.zoneFor(shop, governorate) == null) continue;
            return m;
        }
        return null;
    }

    private static CheckoutService.AddressReq quoteAddress(CheckoutService.AddressReq a) {
        if (a == null || a.governorateCode() == null || a.governorateCode().isBlank()) return null;
        return new CheckoutService.AddressReq(nz(a.recipientName(), "-"), nz(a.phone(), "01000000000"), nz(a.addressLine1(), "-"), a.addressLine2(), nz(a.city(), a.governorateCode()),
                a.state(), a.district(), a.postalCode(), a.governorateCode(), a.area(), a.landmark(), a.phone2());
    }

    private static String nz(String s, String d) { return s == null || s.isBlank() ? d : s; }

    private static BusinessException error(String code, String message) { return BusinessException.badRequest(code, message); }

    // ---- quote -------------------------------------------------------------------------------------------------------

    public Map<String, Object> quote(CartReq r) {
        TreeMap<UUID, List<CheckoutService.Item>> grouped = byShop(r.items());
        Map<UUID, Shop> shops = shops(grouped.keySet());
        Map<UUID, String> hosts = urls.hosts(grouped.keySet());
        CheckoutService.AddressReq qa = quoteAddress(r.address());
        long sub = 0, disc = 0, ship = 0, cod = 0, total = 0;
        String currency = null;
        boolean errors = false;
        List<Map<String, Object>> parts = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            Shop shop = shops.get(e.getKey());
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("shopId", e.getKey());
            part.put("shopSlug", shop == null ? null : shop.slug());
            part.put("shopName", shop == null ? null : shop.name());
            part.put("host", hosts.get(e.getKey()));
            try {
                if (shop == null || !shop.eligible()) throw error("SHOP_UNAVAILABLE", "This shop is not taking orders right now");
                UUID method = null;
                if (qa != null) {
                    Map<String, Object> m = pickShipping(e.getKey(), qa.governorateCode(), r.shipping() == null ? null : r.shipping().get(e.getKey()));
                    if (m == null) throw error("NO_DELIVERY_TO_GOVERNORATE", "Sorry, this shop does not deliver to your governorate yet");
                    method = (UUID) m.get("id");
                    part.put("shippingMethod", Map.of("id", method, "name", m.get("name"), "type", m.get("type")));
                }
                String coupon = r.coupons() == null ? null : r.coupons().get(e.getKey());
                Map<String, Object> q = checkout.quoteView(e.getKey(), null, new CheckoutService.CheckoutReq(e.getValue(), method, qa, "CASH_ON_DELIVERY", coupon, null));
                part.put("quote", q);
                sub += ((Number) q.get("subtotalMinor")).longValue();
                disc += ((Number) q.get("discountMinor")).longValue();
                ship += ((Number) q.get("shippingMinor")).longValue();
                cod += ((Number) q.get("codFeeMinor")).longValue();
                total += ((Number) q.get("totalMinor")).longValue();
                currency = (String) q.get("currency");
            } catch (BusinessException ex) {
                errors = true;
                part.put("error", Map.of("code", ex.getCode(), "message", ex.getMessage()));
            }
            parts.add(part);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shops", parts);
        out.put("currency", currency);
        out.put("subtotalMinor", sub);
        out.put("discountMinor", disc);
        out.put("shippingMinor", ship);
        out.put("codFeeMinor", cod);
        out.put("totalMinor", total);
        out.put("shippingKnown", qa != null);
        out.put("hasErrors", errors);
        return out;
    }

    // ---- place ---------------------------------------------------------------------------------------------------------

    public Map<String, Object> place(UUID accountId, CartReq r, String key) {
        CheckoutService.AddressReq a = r.address();
        if (a == null || blank(a.recipientName()) || blank(a.phone()) || blank(a.addressLine1()) || blank(a.governorateCode()))
            throw BusinessException.badRequest("ADDRESS_REQUIRED", "Your name, mobile number and delivery address are required");
        String phone = PhoneNormalizer.normalize(a.phone());
        if (key != null && (key.isBlank() || key.length() > 50)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid Idempotency-Key");
        if (key != null) {
            UUID prior = jdbc.sql("SELECT id FROM open.order_groups WHERE idempotency_key = :k").param("k", key).query(UUID.class).optional().orElse(null);
            if (prior != null) return placed(prior, List.of());   // the same request again: answer with what it already created
        }

        TreeMap<UUID, List<CheckoutService.Item>> grouped = byShop(r.items());
        Map<UUID, Shop> shops = shops(grouped.keySet());
        // Everything we can check without side effects is checked first, so a bad cart is refused whole instead of half placed.
        Map<UUID, UUID> methods = new LinkedHashMap<>();
        List<Map<String, Object>> problems = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            Shop shop = shops.get(e.getKey());
            try {
                if (shop == null || !shop.eligible()) throw error("SHOP_UNAVAILABLE", "This shop is not taking orders right now");
                Map<String, Object> m = pickShipping(e.getKey(), a.governorateCode(), r.shipping() == null ? null : r.shipping().get(e.getKey()));
                if (m == null) throw error("NO_DELIVERY_TO_GOVERNORATE", "Sorry, this shop does not deliver to your governorate yet");
                methods.put(e.getKey(), (UUID) m.get("id"));
                checkout.quoteView(e.getKey(), null, new CheckoutService.CheckoutReq(e.getValue(), (UUID) m.get("id"), a, "CASH_ON_DELIVERY", coupon(r, e.getKey()), r.notes()));
            } catch (BusinessException ex) {
                problems.add(problem(e.getKey(), shop, ex));
            }
        }
        if (!problems.isEmpty()) throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CART_HAS_PROBLEMS", "Some items in your cart cannot be ordered", Map.of("shops", problems));

        UUID group = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO open.order_groups (id, account_id, phone, name, idempotency_key) VALUES (:i, :a, :p, :n, :k)")
                    .param("i", group).param("a", accountId).param("p", phone).param("n", a.recipientName().trim()).param("k", key).update();
        } catch (DataIntegrityViolationException e) {
            UUID prior = key == null ? null : jdbc.sql("SELECT id FROM open.order_groups WHERE idempotency_key = :k").param("k", key).query(UUID.class).optional().orElse(null);
            if (prior != null) return placed(prior, List.of());
            throw e;
        }
        List<Map<String, Object>> failed = new ArrayList<>();
        int placed = 0;
        for (var e : grouped.entrySet()) {
            Shop shop = shops.get(e.getKey());
            try {
                // each shop's order commits on its own, so one shop running out of stock mid-way never undoes the others
                checkout.placeOrder(e.getKey(), null, new CheckoutService.CheckoutReq(e.getValue(), methods.get(e.getKey()), a, "CASH_ON_DELIVERY", coupon(r, e.getKey()), r.notes()),
                        key == null ? null : "o:" + key + ":" + e.getKey(), 0, "OPEN", group);
                placed++;
            } catch (BusinessException ex) {
                failed.add(problem(e.getKey(), shop, ex));
            }
        }
        if (placed == 0) {
            jdbc.sql("DELETE FROM open.order_groups WHERE id = :i").param("i", group).update();
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CART_HAS_PROBLEMS", "Your order could not be placed", Map.of("shops", failed));
        }
        return placed(group, failed);
    }

    private static String coupon(CartReq r, UUID shop) { return r.coupons() == null ? null : r.coupons().get(shop); }

    private static Map<String, Object> problem(UUID id, Shop shop, BusinessException ex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopId", id);
        m.put("shopName", shop == null ? null : shop.name());
        m.put("code", ex.getCode());
        m.put("message", ex.getMessage());
        return m;
    }

    private Map<String, Object> placed(UUID group, List<Map<String, Object>> failed) {
        Map<String, Object> out = new LinkedHashMap<>(group(group));
        out.put("failed", failed);
        return out;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    // ---- reading orders --------------------------------------------------------------------------------------------------

    /** One purchase with its per-shop orders and their items. The caller has already proven they may see it. */
    public Map<String, Object> group(UUID groupId) {
        var g = jdbc.sql("SELECT id, name, phone, account_id, created_at FROM open.order_groups WHERE id = :i").param("i", groupId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        var orders = Rows.camel(jdbc.sql("""
                SELECT o.id, o.tenant_id AS shop_id, t.slug AS shop_slug, t.name AS shop_name, sp.support_phone, o.order_number, o.status, o.payment_status, o.payment_method, o.currency,
                       o.subtotal_minor, o.discount_minor, o.shipping_minor, o.cod_fee_minor, o.total_minor, o.coupon_code, o.courier_name, o.tracking_number, o.tracking_url,
                       o.eta_min_days, o.eta_max_days, o.created_at, o.confirmed_at, o.shipped_at, o.delivered_at
                FROM commerce.orders o JOIN core.tenants t ON t.id = o.tenant_id LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = o.tenant_id
                WHERE o.open_group_id = :g ORDER BY o.created_at
                """).param("g", groupId).query().listOfRows());
        Map<UUID, String> hosts = urls.hosts(orders.stream().map(o -> (UUID) o.get("shopId")).toList());
        Map<UUID, List<Map<String, Object>>> items = new LinkedHashMap<>();
        if (!orders.isEmpty()) {
            var rows = jdbc.sql("""
                    SELECT i.order_id, i.tenant_id, i.product_id, i.product_name_snapshot AS name, i.variant_name_snapshot AS variant_name, i.quantity, i.unit_price_minor, i.line_total_minor,
                           (SELECT m.url FROM commerce.product_media m WHERE m.product_id = i.product_id ORDER BY m.sort_order LIMIT 1) AS image_url,
                           (SELECT m.media_base FROM commerce.product_media m WHERE m.product_id = i.product_id ORDER BY m.sort_order LIMIT 1) AS image_media_base,
                           (SELECT m.media_ext FROM commerce.product_media m WHERE m.product_id = i.product_id ORDER BY m.sort_order LIMIT 1) AS image_media_ext,
                           (SELECT p.slug FROM commerce.products p WHERE p.id = i.product_id) AS product_slug
                    FROM commerce.order_items i WHERE i.order_id IN (:ids) ORDER BY i.id
                    """).param("ids", orders.stream().map(o -> o.get("id")).toList()).query().listOfRows();
            for (var r : Rows.camel(rows)) {
                String host = hosts.get((UUID) r.get("tenantId"));
                Object base = r.remove("imageMediaBase"), ext = r.remove("imageMediaExt"), url = r.remove("imageUrl");
                r.put("image", host == null ? null : urls.image(host, (String) base, (String) ext, (String) url, "thumb"));
                items.computeIfAbsent((UUID) r.get("orderId"), k -> new ArrayList<>()).add(r);
            }
        }
        long total = 0;
        for (var o : orders) {
            o.put("items", items.getOrDefault((UUID) o.get("id"), List.of()));
            o.put("shopHost", hosts.get((UUID) o.get("shopId")));
            if (!"CANCELLED".equals(o.get("status")) && !"RETURNED".equals(o.get("status"))) total += ((Number) o.get("totalMinor")).longValue();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groupId", groupId);
        out.put("name", g.get("name"));
        out.put("phone", g.get("phone"));
        out.put("createdAt", Rows.camel(Map.of("created_at", g.get("created_at"))).get("createdAt"));
        out.put("orders", orders);
        out.put("totalMinor", total);
        return out;
    }

    /** A guest (or the account holder) opens a purchase with its id and the phone it was placed with. */
    public Map<String, Object> track(UUID groupId, UUID accountId, String phone) {
        var g = jdbc.sql("SELECT account_id, phone FROM open.order_groups WHERE id = :i").param("i", groupId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        boolean owner = accountId != null && accountId.equals(g.get("account_id"));
        boolean phoneOk = phone != null && !phone.isBlank() && PhoneNormalizer.normalize(phone).equals(g.get("phone"));
        if (!owner && !phoneOk) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");   // never reveal that the id exists
        return group(groupId);
    }

    public Map<String, Object> history(UUID accountId, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM open.order_groups g WHERE g.account_id = :a AND EXISTS (SELECT 1 FROM commerce.orders o WHERE o.open_group_id = g.id)").param("a", accountId).query(Long.class).single();
        List<UUID> ids = jdbc.sql("""
                SELECT g.id FROM open.order_groups g WHERE g.account_id = :a AND EXISTS (SELECT 1 FROM commerce.orders o WHERE o.open_group_id = g.id)
                ORDER BY g.created_at DESC LIMIT :lim OFFSET :off
                """).param("a", accountId).param("lim", page.pageSize()).param("off", page.offset()).query(UUID.class).list();
        return page.wrap(ids.stream().map(this::group).toList(), total);
    }

    /** A signed-in shopper reviews a product they received through the marketplace (the shop's moderation and review rules still apply). */
    public void review(UUID accountId, UUID productId, int rating, String text, com.platform.commerce.ShopperService shopper) {
        var p = jdbc.sql("SELECT tenant_id FROM commerce.products WHERE id = :p AND status = 'ACTIVE'").param("p", productId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        boolean bought = jdbc.sql("""
                SELECT count(*) FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id JOIN open.order_groups g ON g.id = o.open_group_id
                WHERE g.account_id = :a AND o.tenant_id = :t AND i.product_id = :p AND o.status = 'ARRIVED'
                """).param("a", accountId).param("t", p).param("p", productId).query(Long.class).single() > 0;
        if (!bought) throw BusinessException.forbidden("REVIEW_NOT_ALLOWED", "Only customers who received this product can review it");
        var acc = jdbc.sql("SELECT name, phone FROM open.accounts WHERE id = :a").param("a", accountId).query().singleRow();
        UUID customer = customers.ensureCustomer(p, null, new StoreCustomerService.Contact((String) acc.get("name"), (String) acc.get("phone"), null, null));
        shopper.addReview(p, customer, productId, rating, text);
    }
}
