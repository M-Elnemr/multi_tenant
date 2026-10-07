package com.platform.commerce;

import com.platform.billing.EntitlementService;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Tenant-scoped dashboard numbers. Day/month boundaries use the tenant's own timezone. */
@Service
public class ReportService {
    private static final String COUNTED = "o.status NOT IN ('PENDING','CANCELLED','RETURN_REQUESTED','RETURNED','REFUNDED')";

    private final JdbcClient jdbc;
    private final EntitlementService ent;

    public ReportService(JdbcClient jdbc, EntitlementService ent) {
        this.jdbc = jdbc;
        this.ent = ent;
    }

    public Map<String, Object> summary(UUID tenantId) {
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        var today = jdbc.sql("SELECT count(*) AS orders, coalesce(sum(total_minor),0) AS sales FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED
                + " AND (o.created_at AT TIME ZONE :tz)::date = (now() AT TIME ZONE :tz)::date").param("t", tenantId).param("tz", tz).query().singleRow();
        var month = jdbc.sql("SELECT count(*) AS orders, coalesce(sum(total_minor),0) AS sales, coalesce(round(avg(total_minor)),0) AS avg FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED
                + " AND date_trunc('month', o.created_at AT TIME ZONE :tz) = date_trunc('month', now() AT TIME ZONE :tz)").param("t", tenantId).param("tz", tz).query().singleRow();
        out.put("ordersToday", today.get("orders"));
        out.put("salesTodayMinor", today.get("sales"));
        out.put("ordersThisMonth", month.get("orders"));
        out.put("salesThisMonthMinor", month.get("sales"));
        out.put("averageOrderValueMinor", month.get("avg"));
        Map<String, Object> byStatus = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS c FROM commerce.orders WHERE tenant_id = :t GROUP BY status ORDER BY status").param("t", tenantId).query().listOfRows()
                .forEach(r -> byStatus.put((String) r.get("status"), r.get("c")));
        out.put("ordersByStatus", byStatus);
        // Cash still to collect: COD orders that are on their way but not yet delivered (delivery marks them paid).
        var cash = jdbc.sql("SELECT count(*) AS c, coalesce(sum(total_minor), 0) AS t FROM commerce.orders WHERE tenant_id = :t AND payment_method = 'CASH_ON_DELIVERY' AND payment_status = 'UNPAID' AND status IN ('CONFIRMED','PROCESSING','PACKED','OUT_FOR_DELIVERY')")
                .param("t", tenantId).query().singleRow();
        out.put("cashToCollectCount", cash.get("c"));
        out.put("cashToCollectMinor", cash.get("t"));
        out.put("cashCollectedTodayMinor", jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM commerce.order_payments WHERE tenant_id = :t AND kind = 'PAYMENT' AND status = 'SUCCEEDED' AND method = 'CASH_ON_DELIVERY' AND (paid_at AT TIME ZONE :tz)::date = (now() AT TIME ZONE :tz)::date")
                .param("t", tenantId).param("tz", tz).query(Long.class).single());
        out.put("lowStockCount", jdbc.sql("SELECT count(*) FROM commerce.inventory_items WHERE tenant_id = :t AND quantity_on_hand - quantity_reserved <= low_stock_threshold").param("t", tenantId).query(Long.class).single());
        if (ent.hasFeature(tenantId, "advanced_reports")) {
            out.put("topProducts", Rows.camel(jdbc.sql("""
                    SELECT i.product_name_snapshot AS name, sum(i.quantity) AS units, sum(i.line_total_minor) AS revenue_minor
                    FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id
                    WHERE o.tenant_id = :t AND """ + " " + COUNTED + """
                     AND o.created_at > now() - interval '30 days' GROUP BY i.product_name_snapshot ORDER BY units DESC LIMIT 10
                    """).param("t", tenantId).query().listOfRows()));
        }
        return out;
    }

}
