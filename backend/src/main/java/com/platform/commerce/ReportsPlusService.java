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

    private static String live(String sql) { return sql.replace("%LIVE%", LIVE); }

    private static final String LIVE = "o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED')";

    private Map<String, Object> period(UUID t, int fromDays, int toDays) {
        var r = jdbc.sql("SELECT count(*) AS orders, coalesce(sum(o.total_minor), 0) AS revenue, count(DISTINCT o.customer_phone_snapshot) AS customers FROM commerce.orders o "
                + "WHERE o.tenant_id = :t AND " + LIVE + " AND o.created_at >= now() - make_interval(days => :f) AND o.created_at < now() - make_interval(days => :e)")
                .param("t", t).param("f", fromDays).param("e", toDays).query().listOfRows().get(0);
        long orders = ((Number) r.get("orders")).longValue(), rev = ((Number) r.get("revenue")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orders", orders);
        m.put("revenueMinor", rev);
        m.put("aovMinor", orders == 0 ? 0 : Math.round((double) rev / orders));
        m.put("customers", ((Number) r.get("customers")).longValue());
        return m;
    }

    /** One call for the whole report centre: totals with the previous period beside them, the daily trend and the splits by channel, payment, area and product. */
    public Map<String, Object> overview(UUID t, int days) {
        int d = Math.max(1, Math.min(days, 365));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("current", period(t, d, 0));
        out.put("previous", period(t, d * 2, d));
        out.put("trend", Rows.camel(jdbc.sql(live("""
                SELECT g.day::date AS day, count(o.id) AS orders, coalesce(sum(o.total_minor), 0) AS revenue_minor
                FROM generate_series((now() - make_interval(days => :d - 1))::date, now()::date, interval '1 day') AS g(day)
                LEFT JOIN commerce.orders o ON o.tenant_id = :t AND o.created_at::date = g.day::date AND %LIVE%
                GROUP BY g.day ORDER BY g.day
                """)).param("t", t).param("d", d).query().listOfRows()));
        out.put("byChannel", split(t, d, "o.channel"));
        out.put("byPayment", split(t, d, "o.payment_method"));
        out.put("byGovernorate", jdbc.sql("SELECT coalesce(nullif(o.governorate_code, ''), '-') AS key, count(*) AS orders, coalesce(sum(o.total_minor), 0) AS revenue_minor FROM commerce.orders o WHERE o.tenant_id = :t AND " + LIVE
                + " AND o.created_at > now() - make_interval(days => :d) GROUP BY 1 ORDER BY 3 DESC LIMIT 8").param("t", t).param("d", d).query().listOfRows().stream().map(Rows::camel).toList());
        out.put("topProducts", Rows.camel(jdbc.sql(live("""
                SELECT i.product_name_snapshot AS name, sum(i.quantity) AS units, sum(i.line_total_minor) AS revenue_minor
                FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id
                WHERE o.tenant_id = :t AND %LIVE%
                 AND o.created_at > now() - make_interval(days => :d)
                GROUP BY i.product_name_snapshot ORDER BY sum(i.line_total_minor) DESC LIMIT 8
                """)).param("t", t).param("d", d).query().listOfRows()));
        var cust = jdbc.sql(live("""
                WITH first AS (SELECT customer_phone_snapshot AS phone, min(created_at) AS first_at FROM commerce.orders WHERE tenant_id = :t AND status NOT IN ('PENDING','CANCELLED') AND customer_phone_snapshot <> '' GROUP BY 1),
                inperiod AS (SELECT DISTINCT customer_phone_snapshot AS phone FROM commerce.orders o WHERE o.tenant_id = :t AND %LIVE%
                 AND o.created_at > now() - make_interval(days => :d) AND customer_phone_snapshot <> '')
                SELECT count(*) FILTER (WHERE f.first_at > now() - make_interval(days => :d)) AS fresh, count(*) FILTER (WHERE f.first_at <= now() - make_interval(days => :d)) AS returning_customers
                FROM inperiod p JOIN first f ON f.phone = p.phone
                """)).param("t", t).param("d", d).query().listOfRows().get(0);
        out.put("customerMix", Map.of("newCustomers", ((Number) cust.get("fresh")).longValue(), "returningCustomers", ((Number) cust.get("returning_customers")).longValue()));
        var ops = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled, count(*) FILTER (WHERE status IN ('RETURNED','REFUNDED')) AS returned,
                       count(*) FILTER (WHERE payment_method = 'CASH_ON_DELIVERY') AS cod, count(*) AS all_orders
                FROM commerce.orders WHERE tenant_id = :t AND created_at > now() - make_interval(days => :d)
                """).param("t", t).param("d", d).query().listOfRows().get(0);
        long ret = jdbc.sql("SELECT coalesce(sum(refund_minor), 0) FROM commerce.order_returns WHERE tenant_id = :t AND created_at > now() - make_interval(days => :d)").param("t", t).param("d", d).query(Long.class).single();
        Map<String, Object> o = new LinkedHashMap<>(Rows.camel(ops));
        o.put("refundedMinor", ret);
        out.put("operations", o);
        out.put("lowStock", Rows.camel(jdbc.sql("""
                SELECT p.name, v.sku, coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) AS available
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id LEFT JOIN commerce.inventory_items i ON i.variant_id = v.id
                WHERE v.tenant_id = :t AND v.status = 'ACTIVE' AND p.status = 'ACTIVE' GROUP BY p.name, v.sku HAVING coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) <= 5
                ORDER BY 3, p.name LIMIT 10
                """).param("t", t).query().listOfRows()));
        return out;
    }

    private List<Map<String, Object>> split(UUID t, int d, String column) {
        return jdbc.sql("SELECT coalesce(nullif(" + column + ", ''), '-') AS key, count(*) AS orders, coalesce(sum(o.total_minor), 0) AS revenue_minor FROM commerce.orders o WHERE o.tenant_id = :t AND " + LIVE
                + " AND o.created_at > now() - make_interval(days => :d) GROUP BY 1 ORDER BY 3 DESC").param("t", t).param("d", d).query().listOfRows().stream().map(Rows::camel).toList();
    }
}
