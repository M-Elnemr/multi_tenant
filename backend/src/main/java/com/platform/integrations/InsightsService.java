package com.platform.integrations;

import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** One picture of every channel: sales, orders, stock health, sync health, trend and best sellers, for the whole shop or for one connected account. */
@Service
public class InsightsService {
    private static final String LIVE = "o.stage NOT IN ('CANCELLED', 'RETURNED')";
    private final JdbcClient jdbc;

    public InsightsService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> overview(UUID tenantId, int days, UUID accountId) {
        int d = Math.max(1, Math.min(days, 365));
        String tz = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("Africa/Cairo");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);

        // per account
        List<Map<String, Object>> channels = Rows.camel(jdbc.sql("""
                SELECT a.id, a.provider, a.kind, a.country, a.label, a.status, a.last_sync_at, a.last_error,
                  (SELECT count(*) FROM integrations.orders o WHERE o.account_id = a.id AND o.placed_at >= now() - make_interval(days => :d)) AS orders,
                  (SELECT coalesce(sum(o.total_minor), 0) FROM integrations.orders o WHERE o.account_id = a.id AND o.placed_at >= now() - make_interval(days => :d) AND %s) AS revenue_minor,
                  (SELECT count(*) FROM integrations.orders o WHERE o.account_id = a.id AND o.stage = 'NEW') AS new_orders,
                  (SELECT count(*) FROM integrations.orders o WHERE o.account_id = a.id AND o.stage IN ('NEW', 'PROCESSING') AND o.placed_at < now() - interval '2 days') AS late_orders,
                  (SELECT count(*) FROM integrations.listings l WHERE l.account_id = a.id) AS listings,
                  (SELECT count(*) FROM integrations.listings l WHERE l.account_id = a.id AND l.status = 'ERROR') AS listing_errors,
                  (SELECT count(*) FROM integrations.listings l WHERE l.account_id = a.id AND l.quantity IS NOT NULL AND l.quantity <= 3) AS low_stock,
                  (SELECT count(*) FROM integrations.listings l WHERE l.account_id = a.id AND l.variant_id IS NULL) AS unlinked
                FROM integrations.accounts a WHERE a.tenant_id = :t AND a.kind IN ('MARKETPLACE', 'STORE_PLATFORM') AND (CAST(:a AS uuid) IS NULL OR a.id = CAST(:a AS uuid)) ORDER BY a.created_at
                """.formatted(LIVE)).param("t", tenantId).param("d", d).param("a", accountId).query().listOfRows());
        for (var c : channels) {
            Providers.Def def = Providers.get((String) c.get("provider"));
            c.put("name", def == null ? c.get("provider") : def.name());
            long orders = ((Number) c.get("orders")).longValue();
            c.put("avgOrderMinor", orders == 0 ? 0 : ((Number) c.get("revenueMinor")).longValue() / orders);
        }
        out.put("channels", channels);

        // the shop's own storefront, for comparison
        if (accountId == null) {
            var own = jdbc.sql("""
                    SELECT count(*) AS orders, coalesce(sum(total_minor), 0) AS revenue FROM commerce.orders WHERE tenant_id = :t AND status NOT IN ('PENDING', 'CANCELLED', 'RETURNED', 'REFUNDED') AND created_at >= now() - make_interval(days => :d)
                    """).param("t", tenantId).param("d", d).query().singleRow();
            out.put("shop", Map.of("orders", own.get("orders"), "revenueMinor", own.get("revenue")));
        }

        // totals
        long orders = 0, revenue = 0, newOrders = 0, errors = 0, lowStock = 0, listings = 0;
        for (var c : channels) {
            orders += ((Number) c.get("orders")).longValue(); revenue += ((Number) c.get("revenueMinor")).longValue(); newOrders += ((Number) c.get("newOrders")).longValue();
            errors += ((Number) c.get("listingErrors")).longValue(); lowStock += ((Number) c.get("lowStock")).longValue(); listings += ((Number) c.get("listings")).longValue();
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("orders", orders); totals.put("revenueMinor", revenue); totals.put("avgOrderMinor", orders == 0 ? 0 : revenue / orders); totals.put("newOrders", newOrders); totals.put("listingErrors", errors); totals.put("lowStock", lowStock); totals.put("listings", listings);
        totals.put("accountsInError", channels.stream().filter((c) -> "ERROR".equals(c.get("status"))).count());
        out.put("totals", totals);

        // day by day: revenue and orders per channel
        out.put("series", Rows.camel(jdbc.sql("""
                SELECT to_char((o.placed_at AT TIME ZONE :tz)::date, 'YYYY-MM-DD') AS day, o.account_id, count(*) AS orders, coalesce(sum(o.total_minor) FILTER (WHERE %s), 0) AS revenue_minor
                FROM integrations.orders o WHERE o.tenant_id = :t AND o.placed_at >= now() - make_interval(days => :d) AND (CAST(:a AS uuid) IS NULL OR o.account_id = CAST(:a AS uuid)) GROUP BY 1, 2 ORDER BY 1
                """.formatted(LIVE)).param("tz", tz).param("t", tenantId).param("d", d).param("a", accountId).query().listOfRows()));

        // funnel and best sellers
        Map<String, Object> funnel = new LinkedHashMap<>();
        for (String s : List.of("NEW", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED")) funnel.put(s, 0L);
        for (var r : jdbc.sql("SELECT o.stage, count(*) AS n FROM integrations.orders o WHERE o.tenant_id = :t AND o.placed_at >= now() - make_interval(days => :d) AND (CAST(:a AS uuid) IS NULL OR o.account_id = CAST(:a AS uuid)) GROUP BY 1")
                .param("t", tenantId).param("d", d).param("a", accountId).query().listOfRows()) funnel.put((String) r.get("stage"), r.get("n"));
        out.put("funnel", funnel);
        out.put("topProducts", Rows.camel(jdbc.sql("""
                SELECT coalesce(nullif(i->>'title', ''), i->>'sku') AS title, i->>'sku' AS sku, sum(coalesce((i->>'quantity')::int, 1)) AS units, sum(coalesce((i->>'totalMinor')::bigint, 0)) AS revenue_minor
                FROM integrations.orders o, jsonb_array_elements(o.items) i
                WHERE o.tenant_id = :t AND o.placed_at >= now() - make_interval(days => :d) AND %s AND (CAST(:a AS uuid) IS NULL OR o.account_id = CAST(:a AS uuid))
                GROUP BY 1, 2 ORDER BY revenue_minor DESC LIMIT 8
                """.formatted(LIVE)).param("t", tenantId).param("d", d).param("a", accountId).query().listOfRows()));

        // what needs attention
        List<Map<String, Object>> attention = new ArrayList<>();
        for (var c : channels) {
            String name = (String) c.get("name");
            if ("ERROR".equals(c.get("status"))) attention.add(Map.of("type", "SYNC_ERROR", "accountId", c.get("id"), "provider", c.get("provider"), "name", name, "message", String.valueOf(c.get("lastError"))));
            if (((Number) c.get("lateOrders")).longValue() > 0) attention.add(Map.of("type", "LATE_ORDERS", "accountId", c.get("id"), "provider", c.get("provider"), "name", name, "count", c.get("lateOrders")));
            if (((Number) c.get("listingErrors")).longValue() > 0) attention.add(Map.of("type", "LISTING_ERRORS", "accountId", c.get("id"), "provider", c.get("provider"), "name", name, "count", c.get("listingErrors")));
            if (((Number) c.get("lowStock")).longValue() > 0) attention.add(Map.of("type", "LOW_STOCK", "accountId", c.get("id"), "provider", c.get("provider"), "name", name, "count", c.get("lowStock")));
            if (((Number) c.get("unlinked")).longValue() > 0) attention.add(Map.of("type", "UNLINKED", "accountId", c.get("id"), "provider", c.get("provider"), "name", name, "count", c.get("unlinked")));
        }
        out.put("attention", attention);
        return out;
    }

    /** The activity log of one account: sync jobs and what they said. */
    public List<Map<String, Object>> activity(UUID tenantId, UUID accountId) {
        return Rows.camel(jdbc.sql("SELECT id, type, status, attempts, last_error, created_at, finished_at FROM integrations.jobs WHERE tenant_id = :t AND account_id = :a ORDER BY id DESC LIMIT 30").param("t", tenantId).param("a", accountId).query().listOfRows());
    }
}
