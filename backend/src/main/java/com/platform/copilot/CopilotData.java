package com.platform.copilot;

import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Plain numbers about one shop for the Copilot: sales by period, stock cover, demand forecast, price ideas. No model involved, so every figure is exact and testable. */
@Component
public class CopilotData {
    private static final String COUNTED = "o.status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED')";
    static final int LEAD_DAYS = 14;   // how long a re-order takes to arrive, assumed until shops can set it

    private final JdbcClient jdbc;

    public CopilotData(JdbcClient jdbc) { this.jdbc = jdbc; }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static double major(Object minor) { return minor instanceof Number n ? n.longValue() / 100.0 : 0; }

    private static int clampDays(int d) { return Math.max(1, Math.min(d, 365)); }

    public String currency(UUID t) { return jdbc.sql("SELECT default_currency FROM core.tenants WHERE id = :t").param("t", t).query(String.class).optional().orElse("EGP"); }

    /** Orders, sales and average order for the last {@code days} days, and for the {@code days} before that. */
    public Map<String, Object> period(UUID t, int days) {
        int d = clampDays(days);
        var cur = jdbc.sql("SELECT count(*) AS orders, coalesce(sum(total_minor),0) AS sales FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED + " AND o.created_at > now() - make_interval(days => :d)")
                .param("t", t).param("d", d).query().singleRow();
        var prev = jdbc.sql("SELECT count(*) AS orders, coalesce(sum(total_minor),0) AS sales FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED + " AND o.created_at <= now() - make_interval(days => :d) AND o.created_at > now() - make_interval(days => :d2)")
                .param("t", t).param("d", d).param("d2", d * 2).query().singleRow();
        long co = ((Number) cur.get("orders")).longValue(), po = ((Number) prev.get("orders")).longValue(), cs = ((Number) cur.get("sales")).longValue(), ps = ((Number) prev.get("sales")).longValue();
        Map<String, Object> m = obj("days", d, "orders", co, "sales", major(cs), "averageOrder", co == 0 ? 0 : major(cs / co), "previousOrders", po, "previousSales", major(ps),
                "salesChangePercent", ps == 0 ? null : Math.round((cs - ps) * 1000.0 / ps) / 10.0, "currency", currency(t));
        m.put("bySource", Rows.camel(jdbc.sql("SELECT source, count(*) AS orders, coalesce(sum(total_minor),0) AS sales_minor FROM commerce.orders o WHERE o.tenant_id = :t AND " + COUNTED + " AND o.created_at > now() - make_interval(days => :d) GROUP BY source ORDER BY orders DESC")
                .param("t", t).param("d", d).query().listOfRows()));
        return m;
    }

    public List<Map<String, Object>> topProducts(UUID t, int days) {
        return Rows.camel(jdbc.sql("""
                SELECT i.product_name_snapshot AS name, sum(i.quantity) AS units, sum(i.line_total_minor) AS revenue_minor FROM commerce.order_items i JOIN commerce.orders o ON o.id = i.order_id
                WHERE o.tenant_id = :t AND """ + " " + COUNTED + " " + """
                 AND o.created_at > now() - make_interval(days => :d) GROUP BY i.product_name_snapshot ORDER BY units DESC LIMIT 8
                """).param("t", t).param("d", clampDays(days)).query().listOfRows());
    }

    /** One row per active product: stock now, units sold in 28 and in the last 7 days, daily rate, and days of cover left. */
    public List<Map<String, Object>> stockCover(UUID t) {
        var rows = jdbc.sql("""
                SELECT p.id, p.name, coalesce(st.stock, 0) AS stock, coalesce(s.u28, 0) AS u28, coalesce(s.u7, 0) AS u7, cost.min_cost, price.min_price
                FROM commerce.products p
                LEFT JOIN (SELECT v.product_id, sum(i.quantity_on_hand - i.quantity_reserved) AS stock FROM commerce.inventory_items i JOIN commerce.product_variants v ON v.id = i.variant_id WHERE i.tenant_id = :t GROUP BY v.product_id) st ON st.product_id = p.id
                LEFT JOIN (SELECT it.product_id, sum(it.quantity) FILTER (WHERE o.created_at > now() - interval '28 days') AS u28, sum(it.quantity) FILTER (WHERE o.created_at > now() - interval '7 days') AS u7
                           FROM commerce.order_items it JOIN commerce.orders o ON o.id = it.order_id WHERE o.tenant_id = :t AND """ + " " + COUNTED + " " + """
                            GROUP BY it.product_id) s ON s.product_id = p.id
                LEFT JOIN (SELECT product_id, min(cost_price_minor) AS min_cost FROM commerce.product_variants WHERE tenant_id = :t AND cost_price_minor IS NOT NULL GROUP BY product_id) cost ON cost.product_id = p.id
                LEFT JOIN (SELECT product_id, min(price_minor) AS min_price FROM commerce.product_variants WHERE tenant_id = :t AND status = 'ACTIVE' GROUP BY product_id) price ON price.product_id = p.id
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE' ORDER BY p.name LIMIT 300
                """).param("t", t).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            long stock = ((Number) r.get("stock")).longValue(), u28 = ((Number) r.get("u28")).longValue(), u7 = ((Number) r.get("u7")).longValue();
            // Recent weeks count more than old ones: a product that took off last week is forecast above its 28-day average.
            double rate = (u28 / 28.0) * 0.5 + (u7 / 7.0) * 0.5;
            Double cover = rate <= 0 ? null : Math.round(Math.max(0, stock) / rate * 10) / 10.0;
            Map<String, Object> m = obj("productId", r.get("id"), "name", r.get("name"), "stock", stock, "unitsLast28Days", u28, "unitsLast7Days", u7, "dailyRate", Math.round(rate * 100) / 100.0, "coverDays", cover,
                    "minPriceMinor", r.get("min_price"), "minCostMinor", r.get("min_cost"));
            out.add(m);
        }
        return out;
    }

    /** Products that will run out before a re-order could arrive, with how many to order to cover the lead time plus a week. */
    public List<Map<String, Object>> reorderSuggestions(UUID t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : stockCover(t)) {
            if (p.get("coverDays") == null) continue;
            double cover = ((Number) p.get("coverDays")).doubleValue(), rate = ((Number) p.get("dailyRate")).doubleValue();
            if (cover > LEAD_DAYS + 7) continue;
            long need = (long) Math.ceil(rate * (LEAD_DAYS + 7)) - ((Number) p.get("stock")).longValue();
            if (need <= 0) continue;
            out.add(obj("name", p.get("name"), "stock", p.get("stock"), "coverDays", cover, "dailyRate", rate, "suggestedOrderQuantity", need, "urgent", cover <= LEAD_DAYS));
        }
        out.sort((a, b) -> Double.compare(((Number) a.get("coverDays")).doubleValue(), ((Number) b.get("coverDays")).doubleValue()));
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    /** Discount ideas for slow movers and a small price rise for fast ones. Discounts never exceed {@code maxDiscountPct} or push the price below cost plus 10%. */
    public List<Map<String, Object>> priceSuggestions(UUID t, int maxDiscountPct) {
        int cap = Math.max(5, Math.min(maxDiscountPct <= 0 ? 10 : maxDiscountPct, 30));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : stockCover(t)) {
            long stock = ((Number) p.get("stock")).longValue(), u28 = ((Number) p.get("unitsLast28Days")).longValue();
            Number price = (Number) p.get("minPriceMinor"), cost = (Number) p.get("minCostMinor");
            if (price == null || price.longValue() <= 0) continue;
            if (stock >= 5 && u28 <= 1) {
                int pct = Math.min(cap, 15);
                long newPrice = price.longValue() * (100 - pct) / 100;
                if (cost != null && newPrice < cost.longValue() * 11 / 10) { pct = (int) Math.max(0, Math.floor(100 - cost.longValue() * 110.0 / price.longValue())); newPrice = price.longValue() * (100 - pct) / 100; }
                if (pct >= 5) out.add(obj("name", p.get("name"), "idea", "DISCOUNT", "percent", pct, "from", major(price), "to", major(newPrice), "sold", u28, "stock", stock, "why", "Slow seller: " + u28 + " sold in 28 days with " + stock + " in stock"));
            } else if (p.get("coverDays") != null && ((Number) p.get("coverDays")).doubleValue() < 7 && u28 >= 8) {
                out.add(obj("name", p.get("name"), "idea", "RAISE_PRICE", "percent", 5, "from", major(price), "to", major(price.longValue() * 105 / 100), "sold", u28, "cover", p.get("coverDays"), "why", "Sells fast (" + u28 + " in 28 days) and stock covers only " + p.get("coverDays") + " days"));
            }
        }
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    public List<Map<String, Object>> campaigns(UUID t) {
        return Rows.camel(jdbc.sql("SELECT name, status, daily_budget_minor, insights::text AS insights FROM marketing.campaigns WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 8").param("t", t).query().listOfRows()).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>(c);
            m.put("insights", Rows.json(c.get("insights")));
            return m;
        }).toList();
    }

    public long unconfirmedOrders(UUID t) { return jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND status = 'REQUESTED' AND confirmed_at IS NULL").param("t", t).query(Long.class).single(); }

    public long unansweredQuestions(UUID t) { return jdbc.sql("SELECT count(*) FROM chat.unanswered WHERE tenant_id = :t AND NOT resolved").param("t", t).query(Long.class).single(); }

    public int maxDiscountPct(UUID t) { return jdbc.sql("SELECT max_discount_pct FROM chat.settings WHERE tenant_id = :t").param("t", t).query(Integer.class).optional().orElse(0); }

    /** Carts left for 24 hours or more and not yet recovered, and what was recovered in 30 days. */
    public Map<String, Object> cartStats(UUID t) {
        var r = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPEN' AND created_at <= now() - interval '24 hours') AS waiting, coalesce(sum(total_minor) FILTER (WHERE status = 'OPEN' AND created_at <= now() - interval '24 hours'), 0) AS waiting_minor,
                       count(*) FILTER (WHERE status = 'RECOVERED' AND recovered_at > now() - interval '30 days') AS recovered, coalesce(sum(total_minor) FILTER (WHERE status = 'RECOVERED' AND recovered_at > now() - interval '30 days'), 0) AS recovered_minor
                FROM commerce.abandoned_carts WHERE tenant_id = :t
                """).param("t", t).query().singleRow();
        return Rows.camel(r);
    }

    public long dueCarts(UUID t) { return ((Number) cartStats(t).get("waiting")).longValue(); }

    public Map<String, Object> segmentCounts(UUID t) {
        Map<String, Object> out = obj("NEW", 0L, "REPEAT", 0L, "VIP", 0L, "AT_RISK", 0L, "LAPSED", 0L);
        for (var r : jdbc.sql(com.platform.commerce.SegmentSql.BASE + "SELECT segment, count(*) AS n FROM s GROUP BY segment").param("t", t).query().listOfRows()) out.put((String) r.get("segment"), ((Number) r.get("n")).longValue());
        return out;
    }
}
