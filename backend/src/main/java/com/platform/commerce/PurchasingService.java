package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers and purchase orders. A draft can be built from what is about to run out; receiving a purchase order adds the goods to stock and records the cost. */
@Service
public class PurchasingService {
    public record Line(UUID variantId, int quantity, Long unitCostMinor) {}
    public record SupplierReq(String name, String phone, String email, String notes) {}
    public record PoReq(UUID supplierId, UUID branchId, String notes, LocalDate expectedAt, List<Line> items) {}

    private static final int LEAD_DAYS = 14;

    private final JdbcClient jdbc;
    private final InventoryService inventory;
    private final AuditService audit;

    public PurchasingService(JdbcClient jdbc, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    // ---- suppliers -------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> suppliers(UUID t) { return Rows.camel(jdbc.sql("SELECT id, name, phone, email, notes, created_at FROM commerce.suppliers WHERE tenant_id = :t ORDER BY name").param("t", t).query().listOfRows()); }

    public Map<String, Object> saveSupplier(UUID t, UUID actor, UUID id, SupplierReq r) {
        if (r.name() == null || r.name().trim().length() < 2) throw BusinessException.badRequest("VALIDATION_ERROR", "Supplier name is required");
        if (id == null) id = jdbc.sql("INSERT INTO commerce.suppliers (tenant_id, name, phone, email, notes) VALUES (:t, :n, :p, :e, :no) RETURNING id").param("t", t).param("n", r.name().trim()).param("p", blank(r.phone())).param("e", blank(r.email())).param("no", r.notes() == null ? "" : r.notes().trim()).query(UUID.class).single();
        else if (jdbc.sql("UPDATE commerce.suppliers SET name = :n, phone = :p, email = :e, notes = :no WHERE id = :i AND tenant_id = :t").param("n", r.name().trim()).param("p", blank(r.phone())).param("e", blank(r.email())).param("no", r.notes() == null ? "" : r.notes().trim()).param("i", id).param("t", t).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Supplier not found");
        audit.record(actor, t, "SUPPLIER_SAVED", "supplier", id, null);
        UUID sid = id;
        return suppliers(t).stream().filter(x -> sid.equals(x.get("id"))).findFirst().orElseThrow();
    }

    public void deleteSupplier(UUID t, UUID id) { jdbc.sql("DELETE FROM commerce.suppliers WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update(); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ---- purchase orders -------------------------------------------------------------------------------------------

    private String nextNumber(UUID t) {
        int year = LocalDate.now().getYear();
        int seq = jdbc.sql("INSERT INTO commerce.counters (tenant_id, kind, year, seq) VALUES (:t, 'PO', :y, 1) ON CONFLICT (tenant_id, kind, year) DO UPDATE SET seq = commerce.counters.seq + 1 RETURNING seq").param("t", t).param("y", year).query(Integer.class).single();
        return "PO-" + year + "-" + String.format("%06d", seq);
    }

    @Transactional
    public Map<String, Object> create(UUID t, UUID actor, PoReq r) {
        if (r.items() == null || r.items().isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Add at least one item");
        if (r.items().size() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "Too many items");
        if (r.supplierId() != null) owned("suppliers", r.supplierId(), t);
        if (r.branchId() != null) owned("branches", r.branchId(), t);
        UUID id = jdbc.sql("INSERT INTO commerce.purchase_orders (tenant_id, supplier_id, number, branch_id, notes, expected_at, created_by) VALUES (:t, :s, :n, :b, :no, :e, :u) RETURNING id")
                .param("t", t).param("s", r.supplierId()).param("n", nextNumber(t)).param("b", r.branchId()).param("no", r.notes() == null ? "" : r.notes().trim())
                .param("e", r.expectedAt() == null ? null : java.sql.Date.valueOf(r.expectedAt())).param("u", actor).query(UUID.class).single();
        long total = 0;
        for (Line l : r.items()) {
            if (l.variantId() == null || l.quantity() < 1 || l.quantity() > 100000) throw BusinessException.badRequest("VALIDATION_ERROR", "Every item needs a variant and a quantity");
            owned("product_variants", l.variantId(), t);
            long cost = l.unitCostMinor() == null ? jdbc.sql("SELECT coalesce(cost_price_minor, 0) FROM commerce.product_variants WHERE id = :v").param("v", l.variantId()).query(Long.class).single() : l.unitCostMinor();
            if (cost < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Cost cannot be negative");
            jdbc.sql("INSERT INTO commerce.purchase_order_items (purchase_id, tenant_id, variant_id, quantity, unit_cost_minor) VALUES (:p, :t, :v, :q, :c)").param("p", id).param("t", t).param("v", l.variantId()).param("q", l.quantity()).param("c", cost).update();
            total += cost * l.quantity();
        }
        jdbc.sql("UPDATE commerce.purchase_orders SET total_minor = :x WHERE id = :i").param("x", total).param("i", id).update();
        audit.record(actor, t, "PURCHASE_ORDER_CREATED", "purchase_order", id, null);
        return get(t, id);
    }

    /** A draft with every variant that will run out before a new order could arrive (same rule as the Copilot: recent weeks weigh more). */
    @Transactional
    public Map<String, Object> draftFromLowStock(UUID t, UUID actor, UUID supplierId) {
        var rows = jdbc.sql("""
                SELECT v.id, v.sku, coalesce(v.cost_price_minor, 0) AS cost,
                       coalesce((SELECT sum(i.quantity_on_hand - i.quantity_reserved) FROM commerce.inventory_items i WHERE i.variant_id = v.id), 0) AS stock,
                       coalesce((SELECT sum(it.quantity) FROM commerce.order_items it JOIN commerce.orders o ON o.id = it.order_id WHERE it.variant_id = v.id AND o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED') AND o.created_at > now() - interval '28 days'), 0) AS u28,
                       coalesce((SELECT sum(it.quantity) FROM commerce.order_items it JOIN commerce.orders o ON o.id = it.order_id WHERE it.variant_id = v.id AND o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED') AND o.created_at > now() - interval '7 days'), 0) AS u7
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id WHERE v.tenant_id = :t AND v.status = 'ACTIVE' AND p.status = 'ACTIVE'
                """).param("t", t).query().listOfRows();
        List<Line> lines = new ArrayList<>();
        for (var r : rows) {
            double rate = ((Number) r.get("u28")).doubleValue() / 28.0 * 0.5 + ((Number) r.get("u7")).doubleValue() / 7.0 * 0.5;
            long stock = ((Number) r.get("stock")).longValue();
            if (rate <= 0 || stock / rate > LEAD_DAYS + 7) continue;
            long need = (long) Math.ceil(rate * (LEAD_DAYS + 7)) - stock;
            if (need > 0) lines.add(new Line((UUID) r.get("id"), (int) Math.min(need, 100000), ((Number) r.get("cost")).longValue()));
        }
        if (lines.isEmpty()) throw BusinessException.conflict("NOTHING_TO_ORDER", "Nothing is about to run out");
        return create(t, actor, new PoReq(supplierId, null, "Drafted from low stock", null, lines));
    }

    private void owned(String table, UUID id, UUID t) {
        if (jdbc.sql("SELECT count(*) FROM commerce." + table + " WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query(Long.class).single() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    public List<Map<String, Object>> list(UUID t) {
        return Rows.camel(jdbc.sql("SELECT po.id, po.number, po.status, po.total_minor, po.expected_at, po.created_at, po.ordered_at, po.received_at, s.name AS supplier_name, (SELECT count(*) FROM commerce.purchase_order_items i WHERE i.purchase_id = po.id) AS item_count "
                + "FROM commerce.purchase_orders po LEFT JOIN commerce.suppliers s ON s.id = po.supplier_id WHERE po.tenant_id = :t ORDER BY po.created_at DESC LIMIT 100").param("t", t).query().listOfRows());
    }

    public Map<String, Object> get(UUID t, UUID id) {
        var po = jdbc.sql("SELECT po.id, po.number, po.status, po.total_minor, po.notes, po.expected_at, po.branch_id, po.created_at, po.ordered_at, po.received_at, po.supplier_id, s.name AS supplier_name FROM commerce.purchase_orders po LEFT JOIN commerce.suppliers s ON s.id = po.supplier_id WHERE po.id = :i AND po.tenant_id = :t")
                .param("i", id).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Purchase order not found"));
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(po));
        m.put("items", Rows.camel(jdbc.sql("SELECT i.id, i.variant_id, i.quantity, i.unit_cost_minor, v.sku, p.name AS product_name, v.combo_key FROM commerce.purchase_order_items i JOIN commerce.product_variants v ON v.id = i.variant_id JOIN commerce.products p ON p.id = v.product_id WHERE i.purchase_id = :i ORDER BY p.name, v.sku").param("i", id).query().listOfRows()));
        return m;
    }

    private Map<String, Object> status(UUID t, UUID id, String from, String to, String stamp) {
        if (jdbc.sql("UPDATE commerce.purchase_orders SET status = :to" + (stamp == null ? "" : ", " + stamp + " = now()") + " WHERE id = :i AND tenant_id = :t AND status = ANY (string_to_array(:f, ','))")
                .param("to", to).param("i", id).param("t", t).param("f", from).update() == 0) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This purchase order cannot be moved to " + to + " now");
        return get(t, id);
    }

    public Map<String, Object> markOrdered(UUID t, UUID actor, UUID id) { audit.record(actor, t, "PURCHASE_ORDER_ORDERED", "purchase_order", id, null); return status(t, id, "DRAFT", "ORDERED", "ordered_at"); }

    public Map<String, Object> cancel(UUID t, UUID actor, UUID id) { audit.record(actor, t, "PURCHASE_ORDER_CANCELLED", "purchase_order", id, null); return status(t, id, "DRAFT,ORDERED", "CANCELLED", null); }

    /** Goods arrived: every line is added to the chosen branch's stock and the variant's cost is updated. */
    @Transactional
    public Map<String, Object> receive(UUID t, UUID actor, UUID id, UUID branchId) {
        var po = get(t, id);
        if (!List.of("DRAFT", "ORDERED").contains(po.get("status"))) throw BusinessException.conflict("INVALID_STATUS_TRANSITION", "This purchase order was already received or cancelled");
        UUID branch = branchId != null ? branchId : (UUID) po.get("branchId");
        if (branch == null) branch = jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", t).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("NO_BRANCH", "Add a branch first"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) po.get("items");
        for (var it : items) {
            inventory.adjust(t, actor, branch, (UUID) it.get("variantId"), ((Number) it.get("quantity")).intValue(), "PURCHASE", "Purchase order " + po.get("number"));
            long cost = ((Number) it.get("unitCostMinor")).longValue();
            if (cost > 0) jdbc.sql("UPDATE commerce.product_variants SET cost_price_minor = :c, updated_at = now() WHERE id = :v AND tenant_id = :t").param("c", cost).param("v", it.get("variantId")).param("t", t).update();
        }
        audit.record(actor, t, "PURCHASE_ORDER_RECEIVED", "purchase_order", id, null);
        return status(t, id, "DRAFT,ORDERED", "RECEIVED", "received_at");
    }
}
