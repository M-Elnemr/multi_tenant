package com.platform.commerce;

import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Margin per product (from the cost the owner entered) and customer cohorts (of the people who first ordered in a month, how many came back later). */
@Service
public class ReportsPlusService {
    private final JdbcClient jdbc;

    public ReportsPlusService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> margin(UUID t, int days) {
        int d = Math.max(1, Math.min(days, 365));
        var rows = jdbc.sql("""
                SELECT i.product_name_snapshot AS name, sum(i.quantity) AS units, sum(i.line_total_minor) AS revenue_minor,
                       sum(i.quantity * coalesce(v.cost_price_minor, 0)) AS cost_minor, bool_and(v.cost_price_minor IS NOT NULL) AS cost_known
                FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id LEFT JOIN commerce.product_variants v ON v.id = i.variant_id
                WHERE o.tenant_id = :t AND o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED') AND o.created_at > now() - make_interval(days => :d)
                GROUP BY i.product_name_snapshot ORDER BY sum(i.line_total_minor) DESC LIMIT 100
                """).param("t", t).param("d", d).query().listOfRows();
        long rev = 0, cost = 0;
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            long rv = ((Number) r.get("revenue_minor")).longValue(), c = ((Number) r.get("cost_minor")).longValue();
            boolean known = Boolean.TRUE.equals(r.get("cost_known"));
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            m.put("marginMinor", known ? rv - c : null);
            m.put("marginPercent", known && rv > 0 ? Math.round((rv - c) * 1000.0 / rv) / 10.0 : null);
            out.add(m);
            if (known) { rev += rv; cost += c; }
        }
        return Map.of("days", d, "products", out, "totalRevenueMinor", rev, "totalCostMinor", cost, "totalMarginMinor", rev - cost, "marginPercent", rev == 0 ? 0 : Math.round((rev - cost) * 1000.0 / rev) / 10.0,
                "note", "Only products with a cost price are counted in the totals.");
    }

    public List<Map<String, Object>> cohorts(UUID t) {
        return Rows.camel(jdbc.sql("""
                WITH o AS (
                  SELECT customer_phone_snapshot AS phone, date_trunc('month', created_at)::date AS month FROM commerce.orders
                  WHERE tenant_id = :t AND status NOT IN ('PENDING','CANCELLED') AND customer_phone_snapshot <> ''
                ), first AS (SELECT phone, min(month) AS cohort FROM o GROUP BY phone)
                SELECT f.cohort AS month, count(DISTINCT f.phone) AS customers,
                       count(DISTINCT o.phone) FILTER (WHERE o.month = f.cohort + interval '1 month') AS back_1,
                       count(DISTINCT o.phone) FILTER (WHERE o.month = f.cohort + interval '2 months') AS back_2,
                       count(DISTINCT o.phone) FILTER (WHERE o.month = f.cohort + interval '3 months') AS back_3
                FROM first f LEFT JOIN o ON o.phone = f.phone GROUP BY f.cohort ORDER BY f.cohort DESC LIMIT 12
                """).param("t", t).query().listOfRows());
    }
}
