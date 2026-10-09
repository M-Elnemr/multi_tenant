package com.platform.commerce;

import com.platform.billing.EntitlementService;
import com.platform.billing.PaymentProvider;
import com.platform.shared.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout (spec 32/33/82): totals are always computed server-side from current prices, stock is
 * reserved under row locks in a stable order (no deadlocks, no oversell), and everything - order,
 * items, reservations, coupon, payment record - commits or rolls back together.
 */
@Service
public class CheckoutService {
    public record Item(UUID variantId, int quantity) {}
    public record AddressReq(String recipientName, String phone, String addressLine1, String addressLine2, String city,
                             String state, String district, String postalCode,
                             String governorateCode, String area, String landmark, String phone2) {}
    public record CheckoutReq(List<Item> items, UUID shippingMethodId, AddressReq address, String paymentMethod, String couponCode, String notes) {}

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final EntitlementService ent;
    private final List<PaymentProvider> providers;
    private final StoreCustomerService customers;
    private final ApplicationEventPublisher events;
    private final com.platform.shared.PaymentPolicy policy;
    private final StoreFulfillmentService fulfilment;
    private final PhoneFlags phoneFlags;

    public CheckoutService(JdbcClient jdbc, InventoryService inventory, EntitlementService ent, List<PaymentProvider> providers,
                           StoreCustomerService customers, ApplicationEventPublisher events, com.platform.shared.PaymentPolicy policy,
                           StoreFulfillmentService fulfilment, PhoneFlags phoneFlags) {
        this.policy = policy;
        this.fulfilment = fulfilment;
        this.phoneFlags = phoneFlags;
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.ent = ent;
        this.providers = providers;
        this.customers = customers;
        this.events = events;
    }

    public Map<String, Object> existingByKey(UUID tenantId, String key) {
        return jdbc.sql("SELECT id, order_number, status, payment_status, total_minor, currency FROM commerce.orders WHERE tenant_id = :t AND idempotency_key = :k")
                .param("t", tenantId).param("k", key).query().listOfRows().stream().findFirst()
                .map(r -> summary(r, null, true)).orElse(null);
    }

    @Transactional
    public Map<String, Object> placeOrder(UUID tenantId, UUID userId, CheckoutReq r, String key) {
        if (key != null && !key.isBlank()) {
            Map<String, Object> prior = existingByKey(tenantId, key);
            if (prior != null) return prior;
        }
        if (r.paymentMethod() == null || r.paymentMethod().isBlank()) r = new CheckoutReq(r.items(), r.shippingMethodId(), r.address(), "CASH_ON_DELIVERY", r.couponCode(), r.notes());
        // userId is the signed-in client, or null for a guest. Either way we need who is buying and how to reach them.
        if (r.address() == null || blank(r.address().recipientName()) || blank(r.address().phone()))
            throw BusinessException.badRequest("CONTACT_REQUIRED", "Your name and mobile number are required");
        String phoneNorm = com.platform.shared.PhoneNormalizer.normalize(r.address().phone());
        if (phoneFlags.isBlocked(tenantId, phoneNorm)) throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_NOT_ALLOWED", "We cannot take this order online. Please contact the shop.");
        UUID customerId = customers.ensureCustomer(tenantId, userId, new StoreCustomerService.Contact(r.address().recipientName(), phoneNorm, null, addressJson(r.address())));
        Quote q = quote(tenantId, customerId, r, true);
        UUID orderId = UUID.randomUUID();

        // Reserve stock. Variants are processed in id order so concurrent checkouts lock rows in the same order.
        Map<UUID, UUID> branchByVariant = new LinkedHashMap<>();
        for (QuoteLine l : q.lines().stream().sorted(Comparator.comparing(QuoteLine::variantId)).toList())
            branchByVariant.put(l.variantId(), inventory.reserve(tenantId, l.variantId(), l.quantity(), orderId));

        boolean card = "CARD".equals(r.paymentMethod());
        String number = nextOrderNumber(tenantId);
        String name = r.address().recipientName().trim();
        String phone = phoneNorm;

        jdbc.sql("""
                INSERT INTO commerce.orders (id, tenant_id, order_number, customer_id, user_id, status, payment_status, payment_method, shipping_method_id, coupon_code,
                    currency, subtotal_minor, discount_minor, shipping_minor, total_minor, customer_name_snapshot, customer_phone_snapshot,
                    shipping_address_snapshot, notes, idempotency_key, reservation_expires_at,
                    governorate_code, area, landmark, phone2, cod_fee_minor, eta_min_days, eta_max_days)
                VALUES (:id, :t, :n, :c, :u, :st, 'UNPAID', :pm, :sm, :cc, :cur, :sub, :disc, :ship, :total, :cn, :cp, CAST(:addr AS jsonb), :notes, :key, :exp,
                    :gov, :area, :lm, :ph2, :codfee, :emin, :emax)
                """).param("id", orderId).param("t", tenantId).param("n", number).param("c", customerId).param("u", userId)
                .param("st", card ? "PENDING" : "REQUESTED").param("pm", r.paymentMethod()).param("sm", r.shippingMethodId()).param("cc", q.couponCode())
                .param("cur", q.currency()).param("sub", q.subtotal()).param("disc", q.discount()).param("ship", q.shipping()).param("total", q.total())
                .param("cn", name).param("cp", phone).param("addr", addressJson(r.address())).param("notes", r.notes())
                .param("key", key == null || key.isBlank() ? null : key)
                .param("exp", card ? java.sql.Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)) : null)
                .param("gov", nz(r.address().governorateCode()).toUpperCase()).param("area", nz(r.address().area())).param("lm", nz(r.address().landmark())).param("ph2", nz(r.address().phone2()))
                .param("codfee", q.codFee()).param("emin", q.etaMin()).param("emax", q.etaMax()).update();

        for (QuoteLine l : q.lines())
            jdbc.sql("""
                    INSERT INTO commerce.order_items (order_id, tenant_id, product_id, variant_id, branch_id, sku_snapshot, product_name_snapshot, variant_name_snapshot,
                        unit_price_minor, quantity, line_total_minor)
                    VALUES (:o, :t, :p, :v, :b, :sku, :pn, :vn, :up, :q, :lt)
                    """).param("o", orderId).param("t", tenantId).param("p", l.productId()).param("v", l.variantId()).param("b", branchByVariant.get(l.variantId()))
                    .param("sku", l.sku()).param("pn", l.productName()).param("vn", l.variantName()).param("up", l.unitPrice()).param("q", l.quantity())
                    .param("lt", l.unitPrice() * l.quantity()).update();

        if (q.couponId() != null) {
            jdbc.sql("UPDATE commerce.coupons SET redemptions = redemptions + 1 WHERE id = :c").param("c", q.couponId()).update();
            jdbc.sql("INSERT INTO commerce.coupon_redemptions (tenant_id, coupon_id, user_id, order_id) VALUES (:t, :c, :u, :o)")
                    .param("t", tenantId).param("c", q.couponId()).param("u", customerId).param("o", orderId).update();
        }
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, NULL, :s, :u, 'Order placed')")
                .param("o", orderId).param("t", tenantId).param("s", card ? "PENDING" : "REQUESTED").param("u", userId).update();

        String checkoutUrl = null;
        String paymentKey = "order-" + orderId;
        if (card) {
            PaymentProvider provider = providers.stream().filter(p -> p.code().equals("mock")).findFirst().orElseThrow();
            var intent = provider.createPaymentIntent(tenantId, orderId, q.total(), q.currency(), paymentKey);
            jdbc.sql("INSERT INTO commerce.order_payments (order_id, tenant_id, provider, method, provider_payment_id, amount_minor, currency, status, idempotency_key) VALUES (:o, :t, :p, 'CARD', :pid, :a, :c, 'PENDING', :k)")
                    .param("o", orderId).param("t", tenantId).param("p", provider.code()).param("pid", intent.providerPaymentId()).param("a", q.total()).param("c", q.currency()).param("k", paymentKey).update();
            checkoutUrl = intent.checkoutUrl();
        } else {
            jdbc.sql("INSERT INTO commerce.order_payments (order_id, tenant_id, method, amount_minor, currency, status, idempotency_key) VALUES (:o, :t, 'CASH_ON_DELIVERY', :a, :c, 'PENDING', :k)")
                    .param("o", orderId).param("t", tenantId).param("a", q.total()).param("c", q.currency()).param("k", paymentKey).update();
        }
        ent.increment(tenantId, "orders_monthly", currentMonth(), 1);
        events.publishEvent(new OrderEvents.OrderCreated(tenantId, orderId, userId, number));
        return summary(Map.of("id", orderId, "order_number", number, "status", card ? "PENDING" : "REQUESTED", "payment_status", "UNPAID",
                "total_minor", q.total(), "currency", q.currency()), checkoutUrl, false);
    }

    // ---- pricing ------------------------------------------------------------------------------------------

    record QuoteLine(UUID productId, UUID variantId, String sku, String productName, String variantName, long unitPrice, int quantity) {}

    record Quote(List<QuoteLine> lines, String currency, long subtotal, long discount, long shipping, long total, UUID couponId, String couponCode, long codFee, Integer etaMin, Integer etaMax) {}

    /** Computes the full price breakdown. With lock=true it also takes the coupon row lock and enforces usage limits. */
    Quote quote(UUID tenantId, UUID customerId, CheckoutReq r, boolean lock) {
        if (r.items() == null || r.items().isEmpty()) throw BusinessException.badRequest("CART_EMPTY", "Cart is empty");
        if (r.items().size() > 50) throw BusinessException.badRequest("CART_TOO_LARGE", "Too many items");
        TreeMap<UUID, Integer> wanted = new TreeMap<>();
        for (Item i : r.items()) {
            if (i.variantId() == null || i.quantity() < 1 || i.quantity() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid quantity");
            wanted.merge(i.variantId(), i.quantity(), Integer::sum);
        }
        if (lock) {
            if (!Set.of("CARD", "CASH_ON_DELIVERY").contains(r.paymentMethod()) || ("CARD".equals(r.paymentMethod()) && !policy.cardEnabled())) throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "Unsupported payment method");
            boolean enabled = jdbc.sql("SELECT enabled FROM commerce.payment_method_settings WHERE tenant_id = :t AND method = :m").param("t", tenantId).param("m", r.paymentMethod())
                    .query(Boolean.class).optional().orElse(false);
            if (!enabled) throw BusinessException.badRequest("PAYMENT_METHOD_DISABLED", "This payment method is not available at this store");
            ent.requireCapacity(tenantId, "max_monthly_orders", ent.usage(tenantId, "orders_monthly", currentMonth()));
        }

        var rows = jdbc.sql("""
                SELECT v.id AS variant_id, v.product_id, v.sku, v.price_minor, v.currency, v.combo_key, p.name
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id
                WHERE v.tenant_id = :t AND v.id IN (:ids) AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                """).param("t", tenantId).param("ids", wanted.keySet()).query().listOfRows();
        if (rows.size() != wanted.size()) throw BusinessException.badRequest("PRODUCT_UNAVAILABLE", "An item in your cart is no longer available");
        List<QuoteLine> lines = new ArrayList<>();
        String currency = null;
        long subtotal = 0;
        for (var row : rows) {
            UUID vid = (UUID) row.get("variant_id");
            long price = ((Number) row.get("price_minor")).longValue();
            int qty = wanted.get(vid);
            String cur = (String) row.get("currency");
            if (currency != null && !currency.equals(cur)) throw BusinessException.badRequest("MIXED_CURRENCY", "Items use different currencies");
            currency = cur;
            String combo = (String) row.get("combo_key");
            lines.add(new QuoteLine((UUID) row.get("product_id"), vid, (String) row.get("sku"), (String) row.get("name"), combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": "), price, qty));
            subtotal += price * qty;
        }

        long shipping = 0, codFee = 0;
        Integer etaMin = null, etaMax = null;
        if (r.shippingMethodId() != null) {
            var sm = jdbc.sql("SELECT type, fee_minor, free_above_minor FROM commerce.shipping_methods WHERE id = :s AND tenant_id = :t AND is_active")
                    .param("s", r.shippingMethodId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.badRequest("SHIPPING_UNAVAILABLE", "Shipping method not available"));
            String type = (String) sm.get("type");
            long fee = ((Number) sm.get("fee_minor")).longValue();
            if ("FIXED".equals(type)) shipping = fee;
            else if ("FREE_ABOVE".equals(type)) shipping = sm.get("free_above_minor") != null && subtotal >= ((Number) sm.get("free_above_minor")).longValue() ? 0 : fee;
            if (!"PICKUP".equals(type) && (r.address() == null || blank(r.address().addressLine1()) || blank(r.address().phone()) || (blank(r.address().city()) && blank(r.address().governorateCode()))))
                throw BusinessException.badRequest("ADDRESS_REQUIRED", "A delivery address is required");
            if ("ZONES".equals(type)) {
                var zone = fulfilment.zoneFor(tenantId, r.address().governorateCode());
                if (zone == null) throw BusinessException.badRequest("NO_DELIVERY_TO_GOVERNORATE", "Sorry, we do not deliver to this governorate yet");
                long zoneFee = ((Number) zone.get("fee_minor")).longValue();
                Object freeAbove = zone.get("free_above_minor");
                shipping = freeAbove != null && subtotal >= ((Number) freeAbove).longValue() ? 0 : zoneFee;
                etaMin = ((Number) zone.get("eta_min_days")).intValue();
                etaMax = ((Number) zone.get("eta_max_days")).intValue();
                if (r.paymentMethod() == null || "CASH_ON_DELIVERY".equals(r.paymentMethod())) codFee = ((Number) zone.get("cod_fee_minor")).longValue();
            }
        } else if (lock) {
            throw BusinessException.badRequest("SHIPPING_UNAVAILABLE", "Choose a shipping method");
        }

        long discount = 0;
        UUID couponId = null;
        String couponCode = null;
        if (r.couponCode() != null && !r.couponCode().isBlank()) {
            ent.requireFeature(tenantId, "coupons");
            var c = jdbc.sql("SELECT * FROM commerce.coupons WHERE tenant_id = :t AND upper(code) = upper(:c)" + (lock ? " FOR UPDATE" : ""))
                    .param("t", tenantId).param("c", r.couponCode().trim()).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.badRequest("COUPON_INVALID", "Invalid coupon"));
            Instant now = Instant.now();
            boolean ok = (Boolean) c.get("is_active")
                    && (c.get("starts_at") == null || !((java.sql.Timestamp) c.get("starts_at")).toInstant().isAfter(now))
                    && (c.get("ends_at") == null || ((java.sql.Timestamp) c.get("ends_at")).toInstant().isAfter(now))
                    && subtotal >= ((Number) c.get("min_order_minor")).longValue()
                    && (c.get("max_redemptions") == null || ((Number) c.get("redemptions")).intValue() < ((Number) c.get("max_redemptions")).intValue());
            if (ok && c.get("per_customer_limit") != null && customerId != null) {
                long used = jdbc.sql("SELECT count(*) FROM commerce.coupon_redemptions WHERE coupon_id = :c AND user_id = :u").param("c", c.get("id")).param("u", customerId).query(Long.class).single();
                ok = used < ((Number) c.get("per_customer_limit")).longValue();
            }
            if (!ok) throw BusinessException.badRequest("COUPON_INVALID", "This coupon cannot be used for this order");
            long value = ((Number) c.get("value")).longValue();
            discount = "PERCENT".equals(c.get("discount_type")) ? subtotal * Math.min(value, 100) / 100 : Math.min(value, subtotal);
            couponId = (UUID) c.get("id");
            couponCode = (String) c.get("code");
        }
        var shop = jdbc.sql("SELECT is_open, closed_message, min_order_minor FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(Map.of());
        if (lock && Boolean.FALSE.equals(shop.get("is_open"))) throw BusinessException.badRequest("SHOP_CLOSED", "The shop is temporarily closed and is not taking orders right now");
        long minOrder = shop.get("min_order_minor") instanceof Number n ? n.longValue() : 0;
        if (minOrder > 0 && subtotal - discount < minOrder) throw BusinessException.badRequest("MIN_ORDER_NOT_REACHED", "The minimum order value is " + (minOrder / 100.0));
        long total = subtotal - discount + shipping + codFee;
        if (total < 0) total = 0;   // never negative (spec 82.5)
        return new Quote(lines, currency, subtotal, discount, shipping, total, couponId, couponCode, codFee, etaMin, etaMax);
    }

    public Map<String, Object> quoteView(UUID tenantId, UUID userId, CheckoutReq r) {
        Quote q = quote(tenantId, userId, r, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", q.currency());
        out.put("subtotalMinor", q.subtotal());
        out.put("discountMinor", q.discount());
        out.put("shippingMinor", q.shipping());
        out.put("codFeeMinor", q.codFee());
        out.put("etaMinDays", q.etaMin());
        out.put("etaMaxDays", q.etaMax());
        out.put("totalMinor", q.total());
        out.put("lines", q.lines().stream().map(l -> Map.of("variantId", l.variantId(), "productName", l.productName(), "unitPriceMinor", l.unitPrice(), "quantity", l.quantity())).toList());
        return out;
    }

    private Map<String, Object> summary(Map<String, Object> r, String checkoutUrl, boolean replay) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", r.get("id"));
        out.put("orderNumber", r.get("order_number"));
        out.put("status", r.get("status"));
        out.put("paymentStatus", r.get("payment_status"));
        out.put("totalMinor", r.get("total_minor"));
        out.put("currency", r.get("currency"));
        if (checkoutUrl != null) out.put("checkoutUrl", checkoutUrl);
        out.put("replay", replay);
        return out;
    }

    private String nextOrderNumber(UUID tenantId) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        int seq = jdbc.sql("""
                INSERT INTO commerce.order_counters (tenant_id, day, seq) VALUES (:t, :d, 1)
                ON CONFLICT (tenant_id, day) DO UPDATE SET seq = commerce.order_counters.seq + 1 RETURNING seq
                """).param("t", tenantId).param("d", java.sql.Date.valueOf(day)).query(Integer.class).single();
        return "ST-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", seq);
    }

    private static String currentMonth() { return java.time.YearMonth.now(ZoneOffset.UTC).toString(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private static String addressJson(AddressReq a) {
        if (a == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipientName", a.recipientName()); m.put("phone", a.phone()); m.put("addressLine1", a.addressLine1());
        m.put("addressLine2", a.addressLine2()); m.put("city", a.city()); m.put("state", a.state()); m.put("district", a.district()); m.put("postalCode", a.postalCode());
        m.put("governorateCode", a.governorateCode()); m.put("area", a.area()); m.put("landmark", a.landmark()); m.put("phone2", a.phone2());
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> { if (v != null) { if (sb.length() > 1) sb.append(','); sb.append('"').append(k).append("\":\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"'); } });
        return sb.append('}').toString();
    }
}
