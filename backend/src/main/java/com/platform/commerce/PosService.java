package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counter sales: the cashier scans a product's QR code (or types the SKU / barcode), takes the money, and the sale is recorded as a paid and delivered order
 * from the chosen branch. The QR printed on product labels holds the product's web address with the SKU, so one code serves customers and the cashier.
 */
@Service
public class PosService {
    public record Line(UUID variantId, int quantity) {}
    public record SaleReq(List<Line> items, UUID branchId, String paymentMethod, Long discountMinor, String customerName, String phone) {}

    static final Set<String> METHODS = Set.of("CASH", "CARD_POS", "TRANSFER");

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final StoreCustomerService customers;
    private final LoyaltyService loyalty;
    private final AuditService audit;
    private final com.platform.open.OpenUrls urls;

    public PosService(JdbcClient jdbc, InventoryService inventory, StoreCustomerService customers, LoyaltyService loyalty, AuditService audit, com.platform.open.OpenUrls urls) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.customers = customers;
        this.loyalty = loyalty;
        this.audit = audit;
        this.urls = urls;
    }

    /** A scanned or typed code: an exact SKU or barcode, or the product address from a label (its v= parameter is the SKU). */
    public Map<String, Object> lookup(UUID t, String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty() || code.length() > 600) throw BusinessException.badRequest("VALIDATION_ERROR", "Scan or type a code");
        String sku = code;
        if (code.startsWith("http")) {
            try {
                String q = URI.create(code).getRawQuery();
                sku = null;
                if (q != null) for (String kv : q.split("&")) if (kv.startsWith("v=")) sku = java.net.URLDecoder.decode(kv.substring(2), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) { sku = null; }
            if (sku == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "This code is not one of your product labels");
        }
        var r = jdbc.sql("""
                SELECT v.id AS variant_id, v.sku, v.price_minor, v.currency, v.combo_key, p.id AS product_id, p.name,
                       coalesce((SELECT sum(i.quantity_on_hand - i.quantity_reserved) FROM commerce.inventory_items i WHERE i.variant_id = v.id), 0) AS available
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id
                WHERE v.tenant_id = :t AND v.status = 'ACTIVE' AND p.status = 'ACTIVE' AND (v.sku = :c OR v.barcode = :c) LIMIT 1
                """).param("t", t).param("c", sku).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "No product has this code"));
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        String combo = (String) r.get("combo_key");
        m.put("variantLabel", combo == null || combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": "));
        m.remove("comboKey");
        return m;
    }

    /** Every sellable variant with the text its label QR should hold: the product's web address plus the SKU, so customers and the cashier use the same code. */
    public List<Map<String, Object>> labels(UUID t, String q) {
        String host = urls.hosts(List.of(t)).get(t);
        String base = urls.baseUrl(host);
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.sql("SELECT v.id AS variant_id, v.sku, v.barcode, v.price_minor, v.currency, v.combo_key, p.name, p.slug FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.tenant_id = :t AND v.status = 'ACTIVE' AND p.status = 'ACTIVE' AND (CAST(:q AS varchar) IS NULL OR p.name ILIKE CAST(:q AS varchar) OR v.sku ILIKE CAST(:q AS varchar)) ORDER BY p.name, v.sku LIMIT 300")
                .param("t", t).param("q", like).query().listOfRows()) {
            String combo = (String) r.get("combo_key");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variantId", r.get("variant_id"));
            m.put("sku", r.get("sku"));
            m.put("name", r.get("name"));
            m.put("variantLabel", combo == null || combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": "));
            m.put("priceMinor", r.get("price_minor"));
            m.put("currency", r.get("currency"));
            m.put("qr", base + "/products/" + java.net.URLEncoder.encode((String) r.get("slug"), java.nio.charset.StandardCharsets.UTF_8) + "?v=" + java.net.URLEncoder.encode((String) r.get("sku"), java.nio.charset.StandardCharsets.UTF_8));
            out.add(m);
        }
        return out;
    }

    private String nextNumber(UUID t) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        int seq = jdbc.sql("INSERT INTO commerce.order_counters (tenant_id, day, seq) VALUES (:t, :d, 1) ON CONFLICT (tenant_id, day) DO UPDATE SET seq = commerce.order_counters.seq + 1 RETURNING seq")
                .param("t", t).param("d", java.sql.Date.valueOf(day)).query(Integer.class).single();
        return "POS-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%06d", seq);
    }

    @Transactional
    public Map<String, Object> sale(UUID t, UUID actor, SaleReq r) {
        if (r.items() == null || r.items().isEmpty()) throw BusinessException.badRequest("CART_EMPTY", "The sale is empty");
        if (r.items().size() > 100) throw BusinessException.badRequest("CART_TOO_LARGE", "Too many items");
        String method = r.paymentMethod() == null ? "CASH" : r.paymentMethod();
        if (!METHODS.contains(method)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown payment method");
        UUID branch = r.branchId() != null ? r.branchId() : jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", t).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("NO_BRANCH", "Add a branch first"));
        if (jdbc.sql("SELECT count(*) FROM commerce.branches WHERE id = :b AND tenant_id = :t AND is_active").param("b", branch).param("t", t).query(Long.class).single() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Branch not found");
        TreeMap<UUID, Integer> wanted = new TreeMap<>();
        for (Line l : r.items()) {
            if (l.variantId() == null || l.quantity() < 1 || l.quantity() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid quantity");
            wanted.merge(l.variantId(), l.quantity(), Integer::sum);
        }
        var rows = jdbc.sql("SELECT v.id, v.product_id, v.sku, v.price_minor, v.currency, v.combo_key, p.name FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.tenant_id = :t AND v.id IN (:ids) AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'")
                .param("t", t).param("ids", wanted.keySet()).query().listOfRows();
        if (rows.size() != wanted.size()) throw BusinessException.badRequest("PRODUCT_UNAVAILABLE", "An item is no longer available");
        rows = new ArrayList<>(rows);
        rows.sort(Comparator.comparing(x -> (UUID) x.get("id")));
        long subtotal = 0;
        String currency = null;
        for (var row : rows) {
            String cur = (String) row.get("currency");
            if (currency != null && !currency.equals(cur)) throw BusinessException.badRequest("MIXED_CURRENCY", "Items use different currencies");
            currency = cur;
            subtotal += ((Number) row.get("price_minor")).longValue() * wanted.get((UUID) row.get("id"));
        }
        long discount = r.discountMinor() == null ? 0 : r.discountMinor();
        if (discount < 0 || discount > subtotal) throw BusinessException.badRequest("VALIDATION_ERROR", "The discount cannot be more than the total");
        long total = subtotal - discount;
        String name = r.customerName() == null || r.customerName().isBlank() ? "Walk-in customer" : r.customerName().trim();
        String phone = "";
        UUID customerId = null;
        if (r.phone() != null && !r.phone().isBlank()) {
            phone = PhoneNormalizer.normalize(r.phone());
            customerId = customers.ensureCustomer(t, null, new StoreCustomerService.Contact(name, phone, null, "{}"));
        }
        UUID orderId = UUID.randomUUID();
        for (var row : rows) inventory.sellFromBranch(t, branch, (UUID) row.get("id"), wanted.get((UUID) row.get("id")), orderId);
        String number = nextNumber(t);
        jdbc.sql("""
                INSERT INTO commerce.orders (id, tenant_id, order_number, customer_id, status, payment_status, fulfillment_status, payment_method, currency, subtotal_minor, discount_minor, shipping_minor, total_minor,
                    customer_name_snapshot, customer_phone_snapshot, source, confirmed_at, delivered_at, risk_level, risk_score)
                VALUES (:id, :t, :n, :c, 'ARRIVED', 'PAID', 'FULFILLED', :pm, :cur, :sub, :disc, 0, :total, :cn, :cp, 'POS', now(), now(), 'LOW', 0)
                """).param("id", orderId).param("t", t).param("n", number).param("c", customerId).param("pm", method).param("cur", currency).param("sub", subtotal).param("disc", discount).param("total", total)
                .param("cn", name).param("cp", phone).update();
        for (var row : rows) {
            int q = wanted.get((UUID) row.get("id"));
            long price = ((Number) row.get("price_minor")).longValue();
            String combo = (String) row.get("combo_key");
            jdbc.sql("INSERT INTO commerce.order_items (order_id, tenant_id, product_id, variant_id, branch_id, sku_snapshot, product_name_snapshot, variant_name_snapshot, unit_price_minor, quantity, line_total_minor) VALUES (:o, :t, :p, :v, :b, :sku, :pn, :vn, :up, :q, :lt)")
                    .param("o", orderId).param("t", t).param("p", row.get("product_id")).param("v", row.get("id")).param("b", branch).param("sku", row.get("sku")).param("pn", row.get("name"))
                    .param("vn", combo == null || combo.isEmpty() ? null : combo.replace("|", ", ").replace("=", ": ")).param("up", price).param("q", q).param("lt", price * q).update();
        }
        jdbc.sql("INSERT INTO commerce.order_payments (order_id, tenant_id, method, amount_minor, currency, status, paid_at, idempotency_key) VALUES (:o, :t, :m, :a, :c, 'SUCCEEDED', now(), :k)")
                .param("o", orderId).param("t", t).param("m", method).param("a", total).param("c", currency).param("k", "pos-" + orderId).update();
        jdbc.sql("INSERT INTO commerce.order_status_history (order_id, tenant_id, from_status, new_status, changed_by, reason) VALUES (:o, :t, NULL, 'ARRIVED', :u, 'Point of sale')").param("o", orderId).param("t", t).param("u", actor).update();
        if (!phone.isEmpty()) loyalty.earnForDelivered(t, orderId, phone, subtotal, discount);
        audit.record(actor, t, "POS_SALE", "order", orderId, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderNumber", number);
        out.put("totalMinor", total);
        out.put("currency", currency);
        out.put("paymentMethod", method);
        return out;
    }

    /** Today's counter sales in the shop's own time zone, split by payment method. */
    public Map<String, Object> today(UUID t) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single();
        var rows = jdbc.sql("SELECT payment_method, count(*) AS sales, coalesce(sum(total_minor), 0) AS total_minor FROM commerce.orders WHERE tenant_id = :t AND source = 'POS' AND (created_at AT TIME ZONE :tz)::date = (now() AT TIME ZONE :tz)::date GROUP BY payment_method")
                .param("t", t).param("tz", tz).query().listOfRows();
        long sales = 0, total = 0;
        for (var r : rows) { sales += ((Number) r.get("sales")).longValue(); total += ((Number) r.get("total_minor")).longValue(); }
        return Map.of("sales", sales, "totalMinor", total, "byMethod", Rows.camel(rows));
    }
}
