package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's side of the till: shifts with their X/Z report (sales by payment, returns, cash in/out, what the drawer should hold against what was counted), the day by
 * cashier and terminal, the sync issues to fix, stock counts to apply, and the till settings (receipt size, footer, the discount a cashier may give alone).
 */
@Service
public class PosReportService {
    private final JdbcClient jdbc;
    private final PosSyncService sync;
    private final InventoryService inventory;
    private final AuditService audit;

    public PosReportService(JdbcClient jdbc, PosSyncService sync, InventoryService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.sync = sync;
        this.inventory = inventory;
        this.audit = audit;
    }

    // ---- shifts ---------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> shifts(UUID t, LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now().minusDays(30) : from, e = to == null ? LocalDate.now() : to;
        return Rows.camel(jdbc.sql("""
                SELECT s.id, s.opened_at, s.closed_at, s.status, s.opening_float_minor, s.counted_cash_minor, s.expected_cash_minor, s.difference_minor, s.handover_kind, s.currency,
                       d.name AS device_name, d.code AS device_code, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS cashier_name,
                       trim(h.first_name || ' ' || coalesce(h.last_name, '')) AS handed_to_name,
                       (SELECT count(*) FROM commerce.orders o WHERE o.pos_shift_id = s.id) AS sales,
                       (SELECT coalesce(sum(o.total_minor), 0) FROM commerce.orders o WHERE o.pos_shift_id = s.id) AS sales_minor
                FROM commerce.pos_shifts s JOIN commerce.pos_devices d ON d.id = s.device_id LEFT JOIN core.users u ON u.id = s.cashier_id LEFT JOIN core.users h ON h.id = s.handed_to
                WHERE s.tenant_id = :t AND s.opened_at::date BETWEEN :f AND :e ORDER BY s.opened_at DESC LIMIT 500
                """).param("t", t).param("f", f).param("e", e).query().listOfRows());
    }

    /** One shift's report (X while open, Z once closed). */
    public Map<String, Object> shift(UUID t, UUID id) {
        var s = jdbc.sql("""
                SELECT s.*, d.name AS device_name, d.code AS device_code, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS cashier_name, trim(h.first_name || ' ' || coalesce(h.last_name, '')) AS handed_to_name
                FROM commerce.pos_shifts s JOIN commerce.pos_devices d ON d.id = s.device_id LEFT JOIN core.users u ON u.id = s.cashier_id LEFT JOIN core.users h ON h.id = s.handed_to
                WHERE s.id = :i AND s.tenant_id = :t
                """).param("i", id).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Shift not found"));
        Map<String, Object> out = new LinkedHashMap<>(Rows.camel(s));
        out.put("byMethod", Rows.camel(jdbc.sql("""
                SELECT p.method, count(DISTINCT o.id) AS sales, sum(p.amount_minor) AS amount_minor FROM commerce.order_payments p JOIN commerce.orders o ON o.id = p.order_id
                WHERE o.pos_shift_id = :s AND p.kind = 'PAYMENT' AND p.status = 'SUCCEEDED' GROUP BY p.method ORDER BY p.method
                """).param("s", id).query().listOfRows()));
        out.put("totals", Rows.camel(jdbc.sql("SELECT count(*) AS sales, coalesce(sum(total_minor), 0) AS total_minor, coalesce(sum(discount_minor), 0) AS discount_minor FROM commerce.orders WHERE pos_shift_id = :s")
                .param("s", id).query().singleRow()));
        out.put("returns", Rows.camel(jdbc.sql("SELECT r.refund_method, count(*) AS returns, coalesce(sum(r.refund_minor), 0) AS refund_minor FROM commerce.order_returns r WHERE r.pos_shift_id = :s GROUP BY r.refund_method")
                .param("s", id).query().listOfRows()));
        out.put("cashMoves", Rows.camel(jdbc.sql("SELECT m.kind, m.amount_minor, m.reason, m.at, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS by_name FROM commerce.pos_cash_moves m LEFT JOIN core.users u ON u.id = m.user_id WHERE m.shift_id = :s ORDER BY m.at")
                .param("s", id).query().listOfRows()));
        out.put("expectedNowMinor", sync.expectedCash(id));
        out.put("topItems", Rows.camel(jdbc.sql("""
                SELECT i.product_name_snapshot AS name, i.variant_name_snapshot AS label, sum(i.quantity) AS qty, sum(i.line_total_minor) AS total_minor
                FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id WHERE o.pos_shift_id = :s GROUP BY 1, 2 ORDER BY sum(i.quantity) DESC LIMIT 10
                """).param("s", id).query().listOfRows()));
        return out;
    }

    /** One day of till sales: totals, by payment, by cashier, by terminal, the best sellers. */
    public Map<String, Object> summary(UUID t, LocalDate day) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single();
        LocalDate d = day == null ? LocalDate.now(java.time.ZoneId.of(tz)) : day;
        String where = " o.tenant_id = :t AND o.source = 'POS' AND (coalesce(o.client_created_at, o.created_at) AT TIME ZONE :tz)::date = :d ";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", d.toString());
        out.put("totals", Rows.camel(jdbc.sql("SELECT count(*) AS sales, coalesce(sum(o.total_minor), 0) AS total_minor, coalesce(sum(o.discount_minor), 0) AS discount_minor, count(*) FILTER (WHERE o.offline) AS offline_sales FROM commerce.orders o WHERE" + where)
                .param("t", t).param("tz", tz).param("d", d).query().singleRow()));
        out.put("byMethod", Rows.camel(jdbc.sql("SELECT p.method, sum(p.amount_minor) AS amount_minor FROM commerce.order_payments p JOIN commerce.orders o ON o.id = p.order_id WHERE" + where + " AND p.kind = 'PAYMENT' GROUP BY p.method ORDER BY 2 DESC")
                .param("t", t).param("tz", tz).param("d", d).query().listOfRows()));
        out.put("byCashier", Rows.camel(jdbc.sql("SELECT trim(coalesce(u.first_name, '—') || ' ' || coalesce(u.last_name, '')) AS name, count(*) AS sales, sum(o.total_minor) AS total_minor FROM commerce.orders o LEFT JOIN core.users u ON u.id = o.cashier_id WHERE" + where + " GROUP BY 1 ORDER BY 3 DESC")
                .param("t", t).param("tz", tz).param("d", d).query().listOfRows()));
        out.put("byDevice", Rows.camel(jdbc.sql("SELECT coalesce(dv.name, '—') AS name, count(*) AS sales, sum(o.total_minor) AS total_minor FROM commerce.orders o LEFT JOIN commerce.pos_devices dv ON dv.id = o.pos_device_id WHERE" + where + " GROUP BY 1 ORDER BY 3 DESC")
                .param("t", t).param("tz", tz).param("d", d).query().listOfRows()));
        out.put("returns", Rows.camel(jdbc.sql("SELECT count(*) AS returns, coalesce(sum(r.refund_minor), 0) AS refund_minor FROM commerce.order_returns r JOIN commerce.orders o ON o.id = r.order_id WHERE o.tenant_id = :t AND o.source = 'POS' AND (r.created_at AT TIME ZONE :tz)::date = :d")
                .param("t", t).param("tz", tz).param("d", d).query().singleRow()));
        out.put("topItems", Rows.camel(jdbc.sql("SELECT i.product_name_snapshot AS name, i.variant_name_snapshot AS label, sum(i.quantity) AS qty, sum(i.line_total_minor) AS total_minor FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id WHERE" + where + " GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 15")
                .param("t", t).param("tz", tz).param("d", d).query().listOfRows()));
        return out;
    }

    // ---- sync issues ----------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> issues(UUID t, boolean openOnly) {
        return Rows.camel(jdbc.sql("""
                SELECT i.id, i.kind, i.detail::text AS detail, i.created_at, i.resolved_at, d.name AS device_name, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS resolved_by_name
                FROM commerce.pos_sync_issues i LEFT JOIN commerce.pos_devices d ON d.id = i.device_id LEFT JOIN core.users u ON u.id = i.resolved_by
                WHERE i.tenant_id = :t AND (NOT :o OR i.resolved_at IS NULL) ORDER BY i.created_at DESC LIMIT 300
                """).param("t", t).param("o", openOnly).query().listOfRows());
    }

    public void resolve(UUID t, UUID actor, UUID id) {
        int n = jdbc.sql("UPDATE commerce.pos_sync_issues SET resolved_at = now(), resolved_by = :u WHERE id = :i AND tenant_id = :t AND resolved_at IS NULL").param("u", actor).param("i", id).param("t", t).update();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Issue not found");
        audit.record(actor, t, "POS_ISSUE_RESOLVED", "pos_sync_issue", id, null);
    }

    // ---- stock counts ---------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> counts(UUID t) {
        return Rows.camel(jdbc.sql("""
                SELECT c.id, c.name, c.status, c.created_at, c.applied_at, b.name AS branch_name, trim(u.first_name || ' ' || coalesce(u.last_name, '')) AS created_by_name,
                       (SELECT count(*) FROM commerce.stock_count_lines l WHERE l.count_id = c.id) AS lines,
                       (SELECT count(*) FROM commerce.stock_count_lines l WHERE l.count_id = c.id AND l.counted <> l.expected_at_count) AS differences
                FROM commerce.stock_counts c JOIN commerce.branches b ON b.id = c.branch_id LEFT JOIN core.users u ON u.id = c.created_by
                WHERE c.tenant_id = :t ORDER BY c.created_at DESC LIMIT 200
                """).param("t", t).query().listOfRows());
    }

    public Map<String, Object> countDetail(UUID t, UUID id) {
        var c = jdbc.sql("SELECT c.id, c.name, c.status, c.branch_id, c.created_at, c.applied_at, b.name AS branch_name FROM commerce.stock_counts c JOIN commerce.branches b ON b.id = c.branch_id WHERE c.id = :i AND c.tenant_id = :t")
                .param("i", id).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Stock count not found"));
        Map<String, Object> out = new LinkedHashMap<>(Rows.camel(c));
        out.put("lines", Rows.camel(jdbc.sql("""
                SELECT l.variant_id, p.name, v.sku, v.combo_key, l.expected_at_count, l.counted, l.counted - l.expected_at_count AS difference, l.counted_at,
                       coalesce(i.quantity_on_hand, 0) AS on_hand_now, v.cost_price_minor
                FROM commerce.stock_count_lines l JOIN commerce.product_variants v ON v.id = l.variant_id JOIN commerce.products p ON p.id = v.product_id
                LEFT JOIN commerce.inventory_items i ON i.variant_id = l.variant_id AND i.branch_id = :b
                WHERE l.count_id = :c ORDER BY (l.counted <> l.expected_at_count) DESC, p.name
                """).param("c", id).param("b", c.get("branch_id")).query().listOfRows()));
        return out;
    }

    /** Applies a count: each line moves the shelf by counted minus what was expected when it was counted, so sales made after the count are kept. */
    @Transactional
    public Map<String, Object> applyCount(UUID t, UUID actor, UUID id) {
        var c = jdbc.sql("SELECT status, branch_id FROM commerce.stock_counts WHERE id = :i AND tenant_id = :t FOR UPDATE").param("i", id).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Stock count not found"));
        if (!Set.of("COUNTING", "REVIEW").contains(c.get("status"))) throw BusinessException.conflict("COUNT_CLOSED", "This stock count was already applied or cancelled");
        int moved = 0, lines = 0;
        for (var l : jdbc.sql("SELECT variant_id, expected_at_count, counted FROM commerce.stock_count_lines WHERE count_id = :c").param("c", id).query().listOfRows()) {
            int delta = ((Number) l.get("counted")).intValue() - ((Number) l.get("expected_at_count")).intValue();
            if (delta == 0) continue;
            moved += Math.abs(inventory.applyCount(t, actor, (UUID) c.get("branch_id"), (UUID) l.get("variant_id"), delta, id));
            lines++;
        }
        jdbc.sql("UPDATE commerce.stock_counts SET status = 'APPLIED', applied_by = :u, applied_at = now() WHERE id = :i").param("u", actor).param("i", id).update();
        audit.record(actor, t, "STOCK_COUNT_APPLIED", "stock_count", id, "{\"lines\":" + lines + ",\"pieces\":" + moved + "}");
        return Map.of("linesChanged", lines, "piecesMoved", moved);
    }

    public void cancelCount(UUID t, UUID actor, UUID id) {
        int n = jdbc.sql("UPDATE commerce.stock_counts SET status = 'CANCELLED' WHERE id = :i AND tenant_id = :t AND status IN ('COUNTING', 'REVIEW')").param("i", id).param("t", t).update();
        if (n == 0) throw BusinessException.conflict("COUNT_CLOSED", "This stock count was already applied or cancelled");
        audit.record(actor, t, "STOCK_COUNT_CANCELLED", "stock_count", id, null);
    }

    // ---- settings -------------------------------------------------------------------------------------------------------

    public Map<String, Object> settings(UUID t) {
        return jdbc.sql("SELECT receipt_size, coalesce(receipt_footer, '') AS receipt_footer, pos_max_discount_pct FROM commerce.store_profiles WHERE tenant_id = :t").param("t", t).query().listOfRows()
                .stream().findFirst().map(Rows::camel).orElse(Map.of("receiptSize", "80", "receiptFooter", "", "posMaxDiscountPct", 10));
    }

    public Map<String, Object> saveSettings(UUID t, UUID actor, String receiptSize, String footer, Integer maxDiscountPct) {
        if (receiptSize != null && !Set.of("58", "80", "A4").contains(receiptSize)) throw BusinessException.badRequest("VALIDATION_ERROR", "Receipt size is 58, 80 or A4");
        if (maxDiscountPct != null && (maxDiscountPct < 0 || maxDiscountPct > 100)) throw BusinessException.badRequest("VALIDATION_ERROR", "The discount is 0 to 100%");
        String f = footer == null ? null : footer.trim().length() > 300 ? footer.trim().substring(0, 300) : footer.trim();
        jdbc.sql("""
                UPDATE commerce.store_profiles SET receipt_size = coalesce(:s, receipt_size), receipt_footer = coalesce(:f, receipt_footer), pos_max_discount_pct = coalesce(:m, pos_max_discount_pct), updated_at = now()
                WHERE tenant_id = :t
                """).param("s", receiptSize).param("f", f).param("m", maxDiscountPct).param("t", t).update();
        audit.record(actor, t, "SETTINGS_CHANGED", "pos_settings", null, null);
        return settings(t);
    }
}
