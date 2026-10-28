package com.platform.open;

import com.platform.commerce.CheckoutService;
import com.platform.commerce.ShopperService;
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
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Checkout in the marketplace. A cart holds products of ONE shop only (so delivery, cash collection and coupons are never split between shops), and
 * the order is placed through the very same code as an order from the shop's own website: the shop's prices, stock, shipping, coupon and COD rules,
 * and the shop's staff are notified exactly the same. The order is only marked source = 'OPEN'.
 */
@Service
public class OpenCheckoutService {
    public record CartItem(UUID variantId, int quantity, String note) {
        public CartItem(UUID variantId, int quantity) { this(variantId, quantity, null); }
    }
    public record CartReq(List<CartItem> items, String couponCode, UUID shippingMethodId, CheckoutService.AddressReq address, String notes) {}

    private final JdbcClient jdbc;
    private final CheckoutService checkout;
    private final StoreSettingsService settings;
    private final StoreFulfillmentService fulfilment;
    private final ShopperService shopper;
    private final OpenUrls urls;

    public OpenCheckoutService(JdbcClient jdbc, CheckoutService checkout, StoreSettingsService settings, StoreFulfillmentService fulfilment, ShopperService shopper, OpenUrls urls) {
        this.jdbc = jdbc;
        this.checkout = checkout;
        this.settings = settings;
        this.fulfilment = fulfilment;
        this.shopper = shopper;
        this.urls = urls;
    }

    private record Shop(UUID id, String slug, String name, boolean eligible) {}

    /** The one shop all the cart's products belong to; a cart with products of two shops is refused. */
    private Shop shopOf(List<CartItem> items) {
        if (items == null || items.isEmpty()) throw BusinessException.badRequest("CART_EMPTY", "Your cart is empty");
        if (items.size() > 50) throw BusinessException.badRequest("CART_TOO_LARGE", "Too many items");
        for (CartItem i : items) if (i.variantId() == null || i.quantity() < 1 || i.quantity() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid quantity");
        var rows = jdbc.sql("""
                SELECT DISTINCT t.id, t.slug, t.name, (t.tenant_type = 'STORE' AND t.status IN ('TRIAL','ACTIVE','PAST_DUE') AND coalesce(sp.is_open, FALSE) AND coalesce(sp.open_listed, FALSE)) AS eligible
                FROM commerce.product_variants v JOIN core.tenants t ON t.id = v.tenant_id LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id
                WHERE v.id IN (:ids)
                """).param("ids", items.stream().map(CartItem::variantId).toList()).query().listOfRows();
        if (rows.isEmpty()) throw BusinessException.badRequest("PRODUCT_UNAVAILABLE", "An item in your cart is no longer available");
        if (rows.size() > 1) throw BusinessException.badRequest("CART_MULTI_SHOP", "A cart can only hold products from one shop");
        var r = rows.get(0);
        Shop shop = new Shop((UUID) r.get("id"), (String) r.get("slug"), (String) r.get("name"), Boolean.TRUE.equals(r.get("eligible")));
        if (!shop.eligible()) throw BusinessException.badRequest("SHOP_UNAVAILABLE", "This shop is not taking orders right now");
        return shop;
    }

    /** The shop's delivery method for this governorate: the shopper's pick when valid, else the first one that delivers there. Null when the shop cannot deliver there. */
    private UUID pickShipping(UUID shop, String governorate, UUID preferred) {
        List<Map<String, Object>> methods = settings.shippingMethods(shop, true);
        if (preferred != null && methods.stream().anyMatch(m -> preferred.equals(m.get("id")))) return preferred;
        for (var m : methods) {
            String type = (String) m.get("type");
            if ("PICKUP".equals(type)) continue;
            if ("ZONES".equals(type) && fulfilment.zoneFor(shop, governorate) == null) continue;
            return (UUID) m.get("id");
        }
        return null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private Map<String, Object> shopView(Shop s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("slug", s.slug());
        m.put("name", s.name());
        m.put("host", urls.hosts(List.of(s.id())).get(s.id()));
        return m;
    }

    // ---- quote -------------------------------------------------------------------------------------------------------

    public Map<String, Object> quote(CartReq r) {
        Shop shop = shopOf(r.items());
        CheckoutService.AddressReq a = r.address();
        boolean known = a != null && !blank(a.governorateCode());
        UUID method = null;
        CheckoutService.AddressReq qa = null;
        if (known) {
            method = pickShipping(shop.id(), a.governorateCode(), r.shippingMethodId());
            if (method == null) throw BusinessException.badRequest("NO_DELIVERY_TO_GOVERNORATE", "Sorry, this shop does not deliver to your governorate yet");
            qa = new CheckoutService.AddressReq(nz(a.recipientName(), "-"), nz(a.phone(), "01000000000"), nz(a.addressLine1(), "-"), a.addressLine2(), nz(a.city(), a.governorateCode()), a.state(), a.district(), a.postalCode(), a.governorateCode(), a.area(), a.landmark(), a.phone2());
        }
        Map<String, Object> q = checkout.quoteView(shop.id(), null, new CheckoutService.CheckoutReq(items(r), method, qa, "CASH_ON_DELIVERY", r.couponCode(), null));
        Map<String, Object> out = new LinkedHashMap<>(q);
        out.put("shop", shopView(shop));
        out.put("shippingKnown", known);
        return out;
    }

    private static String nz(String s, String d) { return blank(s) ? d : s; }

    private static List<CheckoutService.Item> items(CartReq r) {
        List<CheckoutService.Item> out = new ArrayList<>();
        for (CartItem i : r.items()) out.add(new CheckoutService.Item(i.variantId(), i.quantity(), i.note()));
        return out;
    }

    // ---- place ---------------------------------------------------------------------------------------------------------

    public Map<String, Object> place(UUID accountId, CartReq r, String key) {
        CheckoutService.AddressReq a = r.address();
        if (a == null || blank(a.recipientName()) || blank(a.phone()) || blank(a.addressLine1()) || blank(a.governorateCode()))
            throw BusinessException.badRequest("ADDRESS_REQUIRED", "Your name, mobile number and delivery address are required");
        if (key != null && (key.isBlank() || key.length() > 100)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid Idempotency-Key");
        Shop shop = shopOf(r.items());
        UUID method = pickShipping(shop.id(), a.governorateCode(), r.shippingMethodId());
        if (method == null) throw BusinessException.badRequest("NO_DELIVERY_TO_GOVERNORATE", "Sorry, this shop does not deliver to your governorate yet");
        Map<String, Object> placed;
        try {
            placed = checkout.placeOrder(shop.id(), accountId, new CheckoutService.CheckoutReq(items(r), method, a, "CASH_ON_DELIVERY", r.couponCode(), r.notes()), key, 0, "OPEN");
        } catch (DataIntegrityViolationException e) {
            // two identical requests raced on the same Idempotency-Key: the loser returns the winner's order
            Map<String, Object> prior = key == null ? null : checkout.existingByKey(shop.id(), key);
            if (prior == null) throw e;
            placed = prior;
        }
        // the phone typed in the shipping section becomes the account's phone when it has none yet (same account every shop uses)
        if (accountId != null) {
            try { shopper.updateProfile(accountId, null, jdbc.sql("SELECT phone FROM commerce.client_accounts WHERE id = :a").param("a", accountId).query(String.class).optional().orElse(null) == null ? a.phone() : null); }
            catch (RuntimeException ignored) { /* an unusable number must not undo a placed order */ }
        }
        Map<String, Object> out = new LinkedHashMap<>(placed);
        out.put("shop", shopView(shop));
        return out;
    }

    // ---- reading orders ----------------------------------------------------------------------------------------------

    private static final String ORDER_SELECT = """
            SELECT o.id, o.tenant_id AS shop_id, t.slug AS shop_slug, t.name AS shop_name, sp.support_phone, o.order_number, o.status, o.payment_status, o.payment_method, o.currency,
                   o.subtotal_minor, o.discount_minor, o.shipping_minor, o.cod_fee_minor, o.total_minor, o.coupon_code, o.courier_name, o.tracking_number, o.tracking_url,
                   o.eta_min_days, o.eta_max_days, o.customer_name_snapshot AS customer_name, o.customer_phone_snapshot AS customer_phone, o.created_at, o.confirmed_at, o.shipped_at, o.delivered_at
            FROM commerce.orders o JOIN core.tenants t ON t.id = o.tenant_id LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = o.tenant_id
            """;

    private List<Map<String, Object>> withItems(List<Map<String, Object>> orders) {
        if (orders.isEmpty()) return orders;
        Map<UUID, String> hosts = urls.hosts(orders.stream().map(o -> (UUID) o.get("shopId")).toList());
        Map<UUID, List<Map<String, Object>>> items = new LinkedHashMap<>();
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
        for (var o : orders) {
            o.put("items", items.getOrDefault((UUID) o.get("id"), List.of()));
            o.put("shopHost", hosts.get((UUID) o.get("shopId")));
        }
        return orders;
    }

    /** A guest (or anyone) follows an order with the shop, the order number and the phone it was placed with; a wrong phone learns nothing. */
    public Map<String, Object> track(String shopSlug, String orderNumber, String phone) {
        String p;
        try { p = PhoneNormalizer.normalize(phone); } catch (BusinessException e) { p = null; }
        var rows = p == null || blank(orderNumber) ? List.<Map<String, Object>>of()
                : Rows.camel(jdbc.sql(ORDER_SELECT + " WHERE t.slug = :s AND o.order_number = :n AND o.customer_phone_snapshot = :p AND o.source = 'OPEN'")
                        .param("s", shopSlug).param("n", orderNumber.trim()).param("p", p).query().listOfRows());
        if (rows.isEmpty()) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
        return withItems(rows).get(0);
    }

    /** A signed-in shopper opens one of their own orders. */
    public Map<String, Object> order(UUID accountId, UUID orderId) {
        var rows = Rows.camel(jdbc.sql(ORDER_SELECT + " WHERE o.id = :i AND o.user_id = :a").param("i", orderId).param("a", accountId).query().listOfRows());
        if (rows.isEmpty()) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found");
        return withItems(rows).get(0);
    }

    /** Every order the signed-in shopper placed in the marketplace, across shops, newest first. */
    public Map<String, Object> history(UUID accountId, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE user_id = :a AND source = 'OPEN'").param("a", accountId).query(Long.class).single();
        var rows = Rows.camel(jdbc.sql(ORDER_SELECT + " WHERE o.user_id = :a AND o.source = 'OPEN' ORDER BY o.created_at DESC LIMIT :lim OFFSET :off")
                .param("a", accountId).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows());
        return page.wrap(withItems(rows), total);
    }

    /** A signed-in shopper reviews a product they received (the shop's own rules and moderation apply). */
    public void review(UUID accountId, UUID productId, int rating, String text) {
        UUID tenant = jdbc.sql("SELECT tenant_id FROM commerce.products WHERE id = :p AND status = 'ACTIVE'").param("p", productId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        shopper.review(tenant, accountId, productId, rating, text);
    }
}
