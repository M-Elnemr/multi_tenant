package com.platform.commerce;

import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Where the money came from: revenue and orders by channel (website, AI seller, POS, WhatsApp...), what the AI seller sold, what cart recovery brought back, and what the COD guard held back. */
@Service
public class AttributionService {
    private final JdbcClient jdbc;

    public AttributionService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> summary(UUID t, int days) {
        int d = Math.max(1, Math.min(days, 365));
        String counted = "status NOT IN ('PENDING','CANCELLED','RETURNED','REFUNDED')";
        List<Map<String, Object>> bySource = Rows.camel(jdbc.sql("SELECT source, count(*) AS orders, coalesce(sum(total_minor), 0) AS revenue_minor FROM commerce.orders WHERE tenant_id = :t AND " + counted + " AND created_at > now() - make_interval(days => :d) GROUP BY source ORDER BY revenue_minor DESC")
                .param("t", t).param("d", d).query().listOfRows());
        long totalRev = bySource.stream().mapToLong(m -> ((Number) m.get("revenueMinor")).longValue()).sum(), totalOrders = bySource.stream().mapToLong(m -> ((Number) m.get("orders")).longValue()).sum();
        var rec = jdbc.sql("SELECT count(*) AS n, coalesce(sum(total_minor), 0) AS v FROM commerce.abandoned_carts WHERE tenant_id = :t AND status = 'RECOVERED' AND recovered_at > now() - make_interval(days => :d)").param("t", t).param("d", d).query().singleRow();
        var guard = jdbc.sql("SELECT count(*) FILTER (WHERE risk_level = 'HIGH' AND status IN ('CANCELLED','RETURNED')) AS high_lost, coalesce(sum(total_minor) FILTER (WHERE risk_level = 'HIGH' AND status = 'CANCELLED' AND shipped_at IS NULL), 0) AS saved FROM commerce.orders WHERE tenant_id = :t AND created_at > now() - make_interval(days => :d)")
                .param("t", t).param("d", d).query().singleRow();
        long loyaltyOrders = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND " + counted + " AND coupon_code LIKE 'LP-%' AND created_at > now() - make_interval(days => :d)").param("t", t).param("d", d).query(Long.class).single();
        long aiConversations = jdbc.sql("SELECT count(*) FROM chat.conversations WHERE tenant_id = :t AND channel <> 'TEST' AND created_at > now() - make_interval(days => :d)").param("t", t).param("d", d).query(Long.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", d);
        m.put("totalRevenueMinor", totalRev);
        m.put("totalOrders", totalOrders);
        m.put("bySource", bySource);
        m.put("aiSellerRevenueMinor", bySource.stream().filter(x -> "AI_CHAT".equals(x.get("source"))).mapToLong(x -> ((Number) x.get("revenueMinor")).longValue()).sum());
        m.put("aiConversations", aiConversations);
        m.put("recoveredCarts", rec.get("n"));
        m.put("recoveredCartsMinor", rec.get("v"));
        m.put("riskyOrdersLost", guard.get("high_lost"));
        m.put("codSavedMinor", guard.get("saved"));
        m.put("loyaltyOrders", loyaltyOrders);
        return m;
    }
}
