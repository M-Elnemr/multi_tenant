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
    private static final String COUNTED = "o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED')";

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
        var cash = jdbc.sql("SELECT count(*) AS c, coalesce(sum(total_minor), 0) AS t FROM commerce.orders WHERE tenant_id = :t AND payment_method = 'CASH_ON_DELIVERY' AND payment_status = 'UNPAID' AND status IN ('REQUESTED','PREPARING','SHIPPED')")
                .param("t", tenantId).query().singleRow();
        out.put("cashToCollectCount", cash.get("c"));
        out.put("cashToCollectMinor", cash.get("t"));
        out.put("cashCollectedTodayMinor", jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM commerce.order_payments WHERE tenant_id = :t AND kind = 'PAYMENT' AND status = 'SUCCEEDED' AND method = 'CASH_ON_DELIVERY' AND (paid_at AT TIME ZONE :tz)::date = (now() AT TIME ZONE :tz)::date")
                .param("t", tenantId).param("tz", tz).query(Long.class).single());
        out.put("lowStockCount", jdbc.sql("SELECT count(*) FROM commerce.inventory_items WHERE tenant_id = :t AND quantity_on_hand - quantity_reserved <= low_stock_threshold").param("t", tenantId).query(Long.class).single());
        out.put("unconfirmedCount", jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND status = 'REQUESTED' AND confirmed_at IS NULL").param("t", tenantId).query(Long.class).single());
        out.put("openReturnsCount", jdbc.sql("SELECT count(*) FROM commerce.return_requests WHERE tenant_id = :t AND status IN ('REQUESTED','APPROVED')").param("t", tenantId).query(Long.class).single());
        // Delivery outcome of the last 30 days: how many orders reached the customer vs came back or were cancelled (the COD refusal rate).
        var outcome = jdbc.sql("SELECT count(*) FILTER (WHERE status = 'ARRIVED') AS delivered, count(*) FILTER (WHERE status = 'RETURNED' OR status = 'REFUNDED') AS returned, count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled "
                + "FROM commerce.orders WHERE tenant_id = :t AND created_at > now() - interval '30 days'").param("t", tenantId).query().singleRow();
        out.put("delivered30", outcome.get("delivered"));
        out.put("returned30", outcome.get("returned"));
        out.put("cancelled30", outcome.get("cancelled"));
        out.put("salesByDay", Rows.camel(jdbc.sql("SELECT (o.created_at AT TIME ZONE :tz)::date AS day, count(*) AS orders, coalesce(sum(o.total_minor), 0) AS sales_minor FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED
                + " AND o.created_at > now() - interval '14 days' GROUP BY 1 ORDER BY 1").param("t", tenantId).param("tz", tz).query().listOfRows()));
        out.put("byGovernorate", Rows.camel(jdbc.sql("SELECT governorate_code, count(*) AS orders, coalesce(sum(total_minor), 0) AS sales_minor FROM commerce.orders o WHERE o.tenant_id = :t AND governorate_code <> '' AND " + COUNTED
                + " AND o.created_at > now() - interval '30 days' GROUP BY governorate_code ORDER BY orders DESC LIMIT 8").param("t", tenantId).query().listOfRows()));
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
