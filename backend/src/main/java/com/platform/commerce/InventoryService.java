package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock is never changed without a movement row (spec 12). quantity_delta is the signed change to
 * on-hand stock for PURCHASE/SALE/ADJUSTMENT/RETURN/DAMAGE, and to *available* stock for
 * RESERVATION (negative) / RELEASE (positive), which only move the reserved counter.
 */
@Service
public class InventoryService {
    static final Set<String> MANUAL_TYPES = Set.of("PURCHASE", "ADJUSTMENT", "RETURN", "DAMAGE");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Fired once when an order takes a variant's available stock down to its low-stock threshold. */
    public record LowStock(UUID tenantId, UUID variantId, int available) {}

    public InventoryService(JdbcClient jdbc, AuditService audit, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> adjust(UUID tenantId, UUID actor, UUID branchId, UUID variantId, int delta, String type, String reason) {
        if (!MANUAL_TYPES.contains(type)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid movement type");
        if (delta == 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Delta cannot be zero");
        if ("DAMAGE".equals(type) && delta > 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Damage must reduce stock");
        if (("PURCHASE".equals(type) || "RETURN".equals(type)) && delta < 0) throw BusinessException.badRequest("VALIDATION_ERROR", type + " must add stock");
        owned("commerce.branches", branchId, tenantId);
        owned("commerce.product_variants", variantId, tenantId);
        jdbc.sql("INSERT INTO commerce.inventory_items (tenant_id, branch_id, variant_id) VALUES (:t, :b, :v) ON CONFLICT (branch_id, variant_id) DO NOTHING")
                .param("t", tenantId).param("b", branchId).param("v", variantId).update();
        // Atomic, and refuses to go below what is already reserved for open orders.
        int n = jdbc.sql("""
                UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand + :d, updated_at = now()
                WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v AND quantity_on_hand + :d >= quantity_reserved
                """).param("d", delta).param("t", tenantId).param("b", branchId).param("v", variantId).update();
        if (n == 0) throw BusinessException.conflict("INSUFFICIENT_STOCK", "Not enough unreserved stock for this adjustment");
        movement(tenantId, branchId, variantId, type, delta, "adjustment", null, reason, actor);
        audit.record(actor, tenantId, "INVENTORY_ADJUSTED", "product_variant", variantId, "{\"delta\":" + delta + ",\"type\":\"" + type + "\"}");
        return Rows.camel(jdbc.sql("SELECT branch_id, variant_id, quantity_on_hand, quantity_reserved, low_stock_threshold FROM commerce.inventory_items WHERE branch_id = :b AND variant_id = :v AND tenant_id = :t")
                .param("b", branchId).param("v", variantId).param("t", tenantId).query().singleRow());
    }

    public Map<String, Object> list(UUID tenantId, Page page, boolean lowStockOnly) {
        long total = jdbc.sql("SELECT count(*) FROM commerce.inventory_items WHERE tenant_id = :t AND (NOT :l OR quantity_on_hand - quantity_reserved <= low_stock_threshold)")
                .param("t", tenantId).param("l", lowStockOnly).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT i.branch_id, b.name AS branch_name, i.variant_id, v.sku, p.name AS product_name, v.combo_key,
                       i.quantity_on_hand, i.quantity_reserved, i.quantity_on_hand - i.quantity_reserved AS available, i.low_stock_threshold
                FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id
                JOIN commerce.product_variants v ON v.id = i.variant_id JOIN commerce.products p ON p.id = v.product_id
                WHERE i.tenant_id = :t AND (NOT :l OR i.quantity_on_hand - i.quantity_reserved <= i.low_stock_threshold)
                ORDER BY p.name, v.sku LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("l", lowStockOnly).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    // ---- used by checkout / order lifecycle, always inside the caller's transaction ---------------------

    /** Reserves qty from the branch with the most available stock. Caller must lock variants in a stable order. Returns branch id. */
    UUID reserve(UUID tenantId, UUID variantId, int qty, UUID orderId) {
        var rows = jdbc.sql("""
                SELECT i.id, i.branch_id, i.quantity_on_hand - i.quantity_reserved AS available, i.low_stock_threshold
                FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                WHERE i.tenant_id = :t AND i.variant_id = :v
                ORDER BY available DESC, i.id FOR UPDATE OF i
                """).param("t", tenantId).param("v", variantId).query().listOfRows();
        for (var r : rows) {
            if (((Number) r.get("available")).intValue() >= qty) {
                UUID branch = (UUID) r.get("branch_id");
                // the stock is checked again in the update itself: two checkouts racing for the last unit can never both get it
                int done = jdbc.sql("UPDATE commerce.inventory_items SET quantity_reserved = quantity_reserved + :q, updated_at = now() WHERE id = :i AND quantity_on_hand - quantity_reserved >= :q")
                        .param("q", qty).param("i", r.get("id")).update();
                if (done == 0) continue;
                movement(tenantId, branch, variantId, "RESERVATION", -qty, "order", orderId, null, null);
                int before = ((Number) r.get("available")).intValue();
                int threshold = ((Number) r.get("low_stock_threshold")).intValue();
                if (before > threshold && before - qty <= threshold) events.publishEvent(new LowStock(tenantId, variantId, before - qty));
                return branch;
            }
        }
        throw BusinessException.conflict("INSUFFICIENT_STOCK", "Not enough stock");
    }

    void release(UUID tenantId, UUID branchId, UUID variantId, int qty, UUID orderId) {
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_reserved = quantity_reserved - :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v")
                .param("q", qty).param("t", tenantId).param("b", branchId).param("v", variantId).update();
        movement(tenantId, branchId, variantId, "RELEASE", qty, "order", orderId, null, null);
    }

    void sell(UUID tenantId, UUID branchId, UUID variantId, int qty, UUID orderId) {
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand - :q, quantity_reserved = quantity_reserved - :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v")
                .param("q", qty).param("t", tenantId).param("b", branchId).param("v", variantId).update();
        movement(tenantId, branchId, variantId, "SALE", -qty, "order", orderId, null, null);
    }

    void restock(UUID tenantId, UUID branchId, UUID variantId, int qty, UUID orderId) {
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand + :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v")
                .param("q", qty).param("t", tenantId).param("b", branchId).param("v", variantId).update();
        movement(tenantId, branchId, variantId, "RETURN", qty, "order", orderId, "Customer return", null);
    }

    /** Moves stock between two branches in one step; refuses to touch what is reserved for open orders. */
    @Transactional
    public Map<String, Object> transfer(UUID tenantId, UUID actor, UUID variantId, UUID from, UUID to, int qty, String note) {
        if (qty < 1 || qty > 100000) throw BusinessException.badRequest("VALIDATION_ERROR", "Quantity must be at least 1");
        if (from == null || to == null || from.equals(to)) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose two different branches");
        owned("commerce.branches", from, tenantId);
        owned("commerce.branches", to, tenantId);
        owned("commerce.product_variants", variantId, tenantId);
        int n = jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand - :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v AND quantity_on_hand - quantity_reserved >= :q")
                .param("q", qty).param("t", tenantId).param("b", from).param("v", variantId).update();
        if (n == 0) throw BusinessException.conflict("INSUFFICIENT_STOCK", "Not enough free stock in the source branch");
        jdbc.sql("INSERT INTO commerce.inventory_items (tenant_id, branch_id, variant_id) VALUES (:t, :b, :v) ON CONFLICT (branch_id, variant_id) DO NOTHING").param("t", tenantId).param("b", to).param("v", variantId).update();
        jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand + :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v").param("q", qty).param("t", tenantId).param("b", to).param("v", variantId).update();
        UUID id = jdbc.sql("INSERT INTO commerce.stock_transfers (tenant_id, variant_id, from_branch, to_branch, quantity, note, created_by) VALUES (:t, :v, :f, :to, :q, :n, :u) RETURNING id")
                .param("t", tenantId).param("v", variantId).param("f", from).param("to", to).param("q", qty).param("n", note == null ? "" : note.trim()).param("u", actor).query(UUID.class).single();
        movement(tenantId, from, variantId, "TRANSFER_OUT", -qty, "transfer", id, note, actor);
        movement(tenantId, to, variantId, "TRANSFER_IN", qty, "transfer", id, note, actor);
        audit.record(actor, tenantId, "STOCK_TRANSFERRED", "product_variant", variantId, "{\"qty\":" + qty + "}");
        return Map.of("id", id, "quantity", qty);
    }

    public java.util.List<Map<String, Object>> transfers(UUID tenantId) {
        return Rows.camel(jdbc.sql("""
                SELECT t.id, t.quantity, t.note, t.created_at, p.name AS product_name, v.sku, f.name AS from_name, d.name AS to_name
                FROM commerce.stock_transfers t JOIN commerce.product_variants v ON v.id = t.variant_id JOIN commerce.products p ON p.id = v.product_id
                JOIN commerce.branches f ON f.id = t.from_branch JOIN commerce.branches d ON d.id = t.to_branch WHERE t.tenant_id = :t ORDER BY t.created_at DESC LIMIT 50
                """).param("t", tenantId).query().listOfRows());
    }

    /** A counter sale: takes the goods from one chosen branch, right away. */
    void sellFromBranch(UUID tenantId, UUID branchId, UUID variantId, int qty, UUID orderId) {
        int n = jdbc.sql("UPDATE commerce.inventory_items SET quantity_on_hand = quantity_on_hand - :q, updated_at = now() WHERE tenant_id = :t AND branch_id = :b AND variant_id = :v AND quantity_on_hand - quantity_reserved >= :q")
                .param("q", qty).param("t", tenantId).param("b", branchId).param("v", variantId).update();
        if (n == 0) throw BusinessException.conflict("INSUFFICIENT_STOCK", "Not enough stock for one of the items");
        movement(tenantId, branchId, variantId, "SALE", -qty, "order", orderId, "Point of sale", null);
    }

    private void movement(UUID tenantId, UUID branchId, UUID variantId, String type, int delta, String refType, UUID refId, String reason, UUID actor) {
        jdbc.sql("""
                INSERT INTO commerce.inventory_movements (tenant_id, branch_id, variant_id, type, quantity_delta, reference_type, reference_id, reason, created_by)
                VALUES (:t, :b, :v, :ty, :d, :rt, :ri, :r, :a)
                """).param("t", tenantId).param("b", branchId).param("v", variantId).param("ty", type).param("d", delta)
                .param("rt", refType).param("ri", refId).param("r", reason).param("a", actor).update();
    }

    private void owned(String table, UUID id, UUID tenantId) {
        if (jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }
}
