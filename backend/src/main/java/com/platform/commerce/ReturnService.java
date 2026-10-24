package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns by item: the cashier finds the order (by its number), scans the goods that came back, and only those goods are put back in stock,
 * refunded and credited. When every piece of the order has come back the order itself becomes RETURNED.
 */
@Service
public class ReturnService {
    public record Item(UUID orderItemId, int quantity) {}
    public record ReturnReq(UUID orderId, List<Item> items, String reason) {}

    private final JdbcClient jdbc;
    private final OrderService orders;
    private final InventoryService inventory;
    private final InvoiceService invoices;
    private final AuditService audit;

    public ReturnService(JdbcClient jdbc, OrderService orders, InventoryService inventory, InvoiceService invoices, AuditService audit) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.inventory = inventory;
        this.invoices = invoices;
        this.audit = audit;
    }

    /** The order with each line's barcode and how many pieces may still be returned. {@code q} is the order number (a trailing part of it is enough). */
    public Map<String, Object> findOrder(UUID t, String q) {
        String code = q == null ? "" : q.trim();
        if (code.length() < 3) throw BusinessException.badRequest("VALIDATION_ERROR", "Type the order number");
        var o = jdbc.sql("SELECT id, order_number, status, payment_status, currency, total_minor, customer_name_snapshot, customer_phone_snapshot, created_at FROM commerce.orders WHERE tenant_id = :t AND (order_number = :c OR order_number ILIKE :like) ORDER BY (order_number = :c) DESC, created_at DESC LIMIT 1")
                .param("t", t).param("c", code).param("like", "%" + code.replace("%", "").replace("_", "") ).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "No order has this number"));
        UUID orderId = (UUID) o.get("id");
        Map<String, Object> out = new LinkedHashMap<>(Rows.camel(o));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT i.id, i.variant_id, i.sku_snapshot, i.product_name_snapshot, i.variant_name_snapshot, i.quantity, i.line_total_minor, v.barcode,
                       coalesce((SELECT sum(ri.quantity) FROM commerce.order_return_items ri WHERE ri.order_item_id = i.id), 0) AS returned
                FROM commerce.order_items i LEFT JOIN commerce.product_variants v ON v.id = i.variant_id
                WHERE i.order_id = :o ORDER BY i.created_at, i.id
                """).param("o", orderId).query().listOfRows()) {
            Map<String, Object> l = new LinkedHashMap<>();
            int qty = ((Number) r.get("quantity")).intValue(), returned = ((Number) r.get("returned")).intValue();
            l.put("orderItemId", r.get("id"));
            l.put("variantId", r.get("variant_id"));
            l.put("sku", r.get("sku_snapshot"));
            l.put("barcode", r.get("barcode"));
            l.put("name", r.get("product_name_snapshot"));
            l.put("variantName", r.get("variant_name_snapshot"));
            l.put("quantity", qty);
            l.put("returned", returned);
            l.put("returnable", Math.max(0, qty - returned));
            l.put("unitMinor", Math.round(((Number) r.get("line_total_minor")).doubleValue() / qty));
            lines.add(l);
        }
        out.put("items", lines);
        out.put("canReturn", "ARRIVED".equals(o.get("status")) && lines.stream().anyMatch(l -> ((Number) l.get("returnable")).intValue() > 0));
        return out;
    }

    @Transactional
    public Map<String, Object> create(UUID t, UUID actor, ReturnReq r) {
        if (r == null || r.orderId() == null || r.items() == null || r.items().isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose what is being returned");
        var order = orders.lockForUpdate(t, r.orderId());
        if (!"ARRIVED".equals(order.get("status"))) throw BusinessException.conflict("RETURN_NOT_ALLOWED", "Only delivered orders can have items returned");
        var lines = new LinkedHashMap<UUID, Map<String, Object>>();
        for (var row : jdbc.sql("""
                SELECT i.id, i.variant_id, i.branch_id, i.sku_snapshot, i.product_name_snapshot, i.variant_name_snapshot, i.quantity, i.unit_price_minor, i.line_total_minor,
                       coalesce((SELECT sum(ri.quantity) FROM commerce.order_return_items ri WHERE ri.order_item_id = i.id), 0) AS returned
                FROM commerce.order_items i WHERE i.order_id = :o
                """).param("o", r.orderId()).query().listOfRows()) lines.put((UUID) row.get("id"), row);

        Map<UUID, Integer> take = new LinkedHashMap<>();
        for (Item it : r.items()) {
            if (it == null || it.orderItemId() == null || it.quantity() <= 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Each returned item needs a quantity");
            if (!lines.containsKey(it.orderItemId())) throw BusinessException.badRequest("VALIDATION_ERROR", "This item is not part of the order");
            take.merge(it.orderItemId(), it.quantity(), Integer::sum);
        }
        long amount = 0;
        List<Map<String, Object>> creditLines = new ArrayList<>();
        for (var e : take.entrySet()) {
            var l = lines.get(e.getKey());
            int qty = ((Number) l.get("quantity")).intValue(), returned = ((Number) l.get("returned")).intValue();
            if (e.getValue() > qty - returned) throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "RETURN_EXCEEDS_SOLD", "More pieces than were sold, or already returned: " + l.get("product_name_snapshot"));
            long unit = Math.round(((Number) l.get("line_total_minor")).doubleValue() / qty);
            long lineAmount = unit * e.getValue();
            amount += lineAmount;
            Map<String, Object> cl = new LinkedHashMap<>();
            cl.put("name", l.get("product_name_snapshot")); cl.put("variant", l.get("variant_name_snapshot")); cl.put("sku", l.get("sku_snapshot"));
            cl.put("quantity", e.getValue()); cl.put("unitMinor", unit); cl.put("totalMinor", lineAmount);
            creditLines.add(cl);
        }
        String reason = r.reason() == null || r.reason().isBlank() ? null : r.reason().trim();
        if (reason != null && reason.length() > 300) reason = reason.substring(0, 300);

        // cap at what the customer actually paid and has not been given back yet
        long refundable = jdbc.sql("""
                SELECT coalesce((SELECT sum(amount_minor) FROM commerce.order_payments WHERE order_id = :o AND kind = 'PAYMENT' AND status = 'SUCCEEDED'), 0)
                     - coalesce((SELECT sum(amount_minor) FROM commerce.order_payments WHERE order_id = :o AND kind = 'REFUND' AND status = 'SUCCEEDED'), 0)
                """).param("o", r.orderId()).query(Long.class).single();
        long refund = Math.max(0, Math.min(amount, refundable));

        UUID returnId = jdbc.sql("INSERT INTO commerce.order_returns (tenant_id, order_id, refund_minor, reason, created_by) VALUES (:t, :o, :a, :r, :u) RETURNING id")
                .param("t", t).param("o", r.orderId()).param("a", refund).param("r", reason).param("u", actor).query(UUID.class).single();
        for (var e : take.entrySet()) {
            var l = lines.get(e.getKey());
            long unit = Math.round(((Number) l.get("line_total_minor")).doubleValue() / ((Number) l.get("quantity")).intValue());
            jdbc.sql("INSERT INTO commerce.order_return_items (return_id, order_item_id, quantity, amount_minor) VALUES (:r, :i, :q, :a)")
                    .param("r", returnId).param("i", e.getKey()).param("q", e.getValue()).param("a", unit * e.getValue()).update();
            if (l.get("variant_id") != null && l.get("branch_id") != null)
                inventory.restock(t, (UUID) l.get("branch_id"), (UUID) l.get("variant_id"), e.getValue(), r.orderId());
        }

        boolean all = lines.entrySet().stream().allMatch(en -> ((Number) en.getValue().get("returned")).intValue() + take.getOrDefault(en.getKey(), 0) >= ((Number) en.getValue().get("quantity")).intValue());
        if (all) orders.markFullyReturned(t, order, actor, reason == null ? "All items returned" : reason);
        if (refund > 0) orders.refund(t, actor, r.orderId(), refund, reason == null ? "Item return" : reason);

        Map<String, Object> note = invoices.creditNoteForReturn(t, actor, r.orderId(), returnId, creditLines, amount, reason);
        audit.record(actor, t, "ORDER_ITEMS_RETURNED", "order", r.orderId(), "{\"returnId\":\"" + returnId + "\",\"refundMinor\":" + refund + "}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returnId", returnId);
        out.put("refundMinor", refund);
        out.put("valueMinor", amount);
        out.put("orderFullyReturned", all);
        out.put("creditNote", note);
        return out;
    }

    public List<Map<String, Object>> recent(UUID t) {
        return Rows.camel(jdbc.sql("""
                SELECT r.id, r.created_at, r.refund_minor, r.reason, o.order_number, o.currency,
                       (SELECT coalesce(sum(quantity), 0) FROM commerce.order_return_items ri WHERE ri.return_id = r.id) AS pieces
                FROM commerce.order_returns r JOIN commerce.orders o ON o.id = r.order_id WHERE r.tenant_id = :t ORDER BY r.created_at DESC LIMIT 30
                """).param("t", t).query().listOfRows());
    }
}
