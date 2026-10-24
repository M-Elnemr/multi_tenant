package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A/B test of the announcement bar text. Visitors are split 50/50 by the browser; each visitor is counted once, orders are counted once. The result says which text
 * ordered better and whether the difference is big enough to trust (two-proportion test, 95%); the owner decides and applies the winner.
 */
@Service
public class ExperimentService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public ExperimentService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---- storefront ------------------------------------------------------------------------------------------------------

    public Map<String, Object> active(UUID t) {
        var r = jdbc.sql("SELECT id, variant_a, variant_b FROM commerce.experiments WHERE tenant_id = :t AND status = 'RUNNING'").param("t", t).query().listOfRows().stream().findFirst().orElse(null);
        return r == null ? Map.of() : Map.of("id", r.get("id"), "a", r.get("variant_a"), "b", r.get("variant_b"));
    }

    public void event(UUID t, UUID experimentId, String variant, String type, String clientKey, String orderNumber) {
        if (!"A".equals(variant) && !"B".equals(variant)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown variant");
        if (!"view".equals(type) && !"order".equals(type)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown event");
        if (clientKey == null || !clientKey.matches("[A-Za-z0-9_-]{8,80}")) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid visitor id");
        if (jdbc.sql("SELECT count(*) FROM commerce.experiments WHERE id = :e AND tenant_id = :t AND status = 'RUNNING'").param("e", experimentId).param("t", t).query(Long.class).single() == 0) return;   // a stopped test records nothing
        if ("order".equals(type)) {
            // only a real order of this shop can count, and only once
            if (orderNumber == null || jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND order_number = :n AND created_at > now() - interval '2 hours'").param("t", t).param("n", orderNumber).query(Long.class).single() == 0) return;
        }
        jdbc.sql("INSERT INTO commerce.experiment_events (experiment_id, variant, type, client_key, order_number) VALUES (:e, :v, :ty, :c, :o) ON CONFLICT DO NOTHING")
                .param("e", experimentId).param("v", variant).param("ty", type).param("c", clientKey).param("o", "order".equals(type) ? orderNumber : null).update();
    }

    // ---- owner -------------------------------------------------------------------------------------------------------------

    public Map<String, Object> start(UUID t, UUID actor, String name, String a, String b) {
        String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
        if (x.length() < 3 || y.length() < 3 || x.length() > 200 || y.length() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "Each text must be 3 to 200 characters");
        if (x.equalsIgnoreCase(y)) throw BusinessException.badRequest("VALIDATION_ERROR", "The two texts must be different");
        jdbc.sql("UPDATE commerce.experiments SET status = 'STOPPED', ended_at = now() WHERE tenant_id = :t AND status = 'RUNNING'").param("t", t).update();
        UUID id = jdbc.sql("INSERT INTO commerce.experiments (tenant_id, name, variant_a, variant_b, created_by) VALUES (:t, :n, :a, :b, :u) RETURNING id")
                .param("t", t).param("n", name == null || name.isBlank() ? "Announcement test" : name.trim()).param("a", x).param("b", y).param("u", actor).query(UUID.class).single();
        audit.record(actor, t, "EXPERIMENT_STARTED", "experiment", id, null);
        return results(t, id);
    }

    public List<Map<String, Object>> list(UUID t) {
        return jdbc.sql("SELECT id FROM commerce.experiments WHERE tenant_id = :t ORDER BY started_at DESC LIMIT 20").param("t", t).query(UUID.class).list().stream().map(id -> results(t, id)).toList();
    }

    public Map<String, Object> stop(UUID t, UUID actor, UUID id) {
        if (jdbc.sql("UPDATE commerce.experiments SET status = 'STOPPED', ended_at = now() WHERE id = :i AND tenant_id = :t AND status = 'RUNNING'").param("i", id).param("t", t).update() == 0) throw BusinessException.conflict("NOT_RUNNING", "This test is not running");
        audit.record(actor, t, "EXPERIMENT_STOPPED", "experiment", id, null);
        return results(t, id);
    }

    /** Puts the chosen text on the announcement bar for everyone and stops the test. */
    public Map<String, Object> apply(UUID t, UUID actor, UUID id, String winner) {
        if (!"A".equals(winner) && !"B".equals(winner)) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose A or B");
        var e = jdbc.sql("SELECT variant_a, variant_b FROM commerce.experiments WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Test not found"));
        jdbc.sql("UPDATE commerce.store_profiles SET announcement = :a WHERE tenant_id = :t").param("a", "A".equals(winner) ? e.get("variant_a") : e.get("variant_b")).param("t", t).update();
        jdbc.sql("UPDATE commerce.experiments SET status = 'STOPPED', ended_at = coalesce(ended_at, now()), winner = :w WHERE id = :i AND tenant_id = :t").param("w", winner).param("i", id).param("t", t).update();
        audit.record(actor, t, "EXPERIMENT_APPLIED", "experiment", id, "{\"winner\":\"" + winner + "\"}");
        return results(t, id);
    }

    public Map<String, Object> results(UUID t, UUID id) {
        var e = jdbc.sql("SELECT id, name, variant_a, variant_b, status, winner, started_at, ended_at FROM commerce.experiments WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Test not found"));
        var c = jdbc.sql("""
                SELECT count(*) FILTER (WHERE variant = 'A' AND type = 'view') AS views_a, count(*) FILTER (WHERE variant = 'B' AND type = 'view') AS views_b,
                       count(*) FILTER (WHERE variant = 'A' AND type = 'order') AS orders_a, count(*) FILTER (WHERE variant = 'B' AND type = 'order') AS orders_b
                FROM commerce.experiment_events WHERE experiment_id = :i
                """).param("i", id).query().singleRow();
        long va = ((Number) c.get("views_a")).longValue(), vb = ((Number) c.get("views_b")).longValue(), oa = ((Number) c.get("orders_a")).longValue(), ob = ((Number) c.get("orders_b")).longValue();
        double ra = va == 0 ? 0 : (double) oa / va, rb = vb == 0 ? 0 : (double) ob / vb;
        // two-proportion z-test; "enough data" also needs at least 100 visitors on each side and 10 orders overall
        double z = 0;
        if (va > 0 && vb > 0) {
            double p = (double) (oa + ob) / (va + vb), se = Math.sqrt(p * (1 - p) * (1.0 / va + 1.0 / vb));
            z = se == 0 ? 0 : (rb - ra) / se;
        }
        boolean enough = va >= 100 && vb >= 100 && oa + ob >= 10;
        boolean significant = enough && Math.abs(z) >= 1.96;
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(e));
        m.put("viewsA", va); m.put("viewsB", vb); m.put("ordersA", oa); m.put("ordersB", ob);
        m.put("rateA", Math.round(ra * 10000) / 100.0); m.put("rateB", Math.round(rb * 10000) / 100.0);
        m.put("enoughData", enough);
        m.put("significant", significant);
        m.put("recommendation", !significant ? null : rb > ra ? "B" : "A");
        return m;
    }
}
