package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Scores every new order from 0 (safe) to 100 (very likely to be refused or fake) from what the shop already knows: this phone's history, how complete the
 * address is, how big the order is compared with the shop's usual, and how many orders came from the same phone today. The score only labels the order;
 * whether a risky order is held until the owner confirms it is the owner's choice ({@code commerce.cod_settings}, off by default).
 */
@Service
public class CodRiskService {
    public record Risk(int score, String level, List<String> reasons) {}

    private final JdbcClient jdbc;
    private final PhoneFlags flags;
    private final AuditService audit;

    public CodRiskService(JdbcClient jdbc, PhoneFlags flags, AuditService audit) {
        this.jdbc = jdbc;
        this.flags = flags;
        this.audit = audit;
    }

    /** Called while an order is being placed (the new order is not stored yet). Cash on delivery only: a paid order is not a COD risk. */
    public Risk assess(UUID tenantId, String phone, String name, String addressLine, String governorate, long totalMinor, int quantity, boolean cod) {
        if (!cod) return new Risk(0, "LOW", List.of());
        int score = 0;
        List<String> why = new ArrayList<>();
        var st = flags.stats(tenantId, phone);
        int delivered = ((Number) st.get("deliveredCount")).intValue(), returned = ((Number) st.get("returnedCount")).intValue(), cancelled = ((Number) st.get("cancelledCount")).intValue();
        if (returned > 0) { score += Math.min(60, 35 * returned); why.add("RETURNED_BEFORE"); }
        if (cancelled > 0 && delivered == 0) { score += Math.min(40, 20 * cancelled); why.add("CANCELLED_BEFORE"); }
        if (delivered >= 2 && returned == 0) { score -= 20; why.add("GOOD_HISTORY"); }
        long today = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND customer_phone_snapshot = :p AND created_at > now() - interval '24 hours'").param("t", tenantId).param("p", phone).query(Long.class).single();
        if (today >= 2) { score += 25; why.add("MANY_TODAY"); }
        long ever = jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t AND customer_phone_snapshot = :p").param("t", tenantId).param("p", phone).query(Long.class).single();
        long avg = jdbc.sql("SELECT coalesce(avg(total_minor), 0)::bigint FROM commerce.orders WHERE tenant_id = :t AND status NOT IN ('CANCELLED','RETURNED','REFUNDED') AND created_at > now() - interval '90 days'").param("t", tenantId).query(Long.class).single();
        if (ever == 0 && avg > 0 && totalMinor > avg * 3) { score += 20; why.add("FIRST_ORDER_BIG"); }
        if (quantity >= 6) { score += 10; why.add("LARGE_QUANTITY"); }
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.matches(".*(\\d)\\1{6,}.*") || digits.matches(".*(0123456|1234567|2345678|3456789).*")) { score += 30; why.add("FAKE_LOOKING_PHONE"); }
        if (name == null || name.trim().length() < 4 || name.trim().split("\\s+").length < 2) { score += 10; why.add("SHORT_NAME"); }
        String addr = addressLine == null ? "" : addressLine.trim();
        if (!addr.isEmpty() && "Pickup from the shop".equals(addr)) { /* pickup: nothing to check */ }
        else if (addr.length() < 12 || !addr.matches(".*[\\p{L}].*")) { score += 15; why.add("THIN_ADDRESS"); }
        score = Math.max(0, Math.min(100, score));
        return new Risk(score, score >= 60 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW", why);
    }

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT hold_high_risk, hold_medium_risk FROM commerce.cod_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holdHighRisk", r != null && Boolean.TRUE.equals(r.get("hold_high_risk")));
        m.put("holdMediumRisk", r != null && Boolean.TRUE.equals(r.get("hold_medium_risk")));
        return m;
    }

    public Map<String, Object> saveSettings(UUID tenantId, UUID actor, Boolean holdHigh, Boolean holdMedium) {
        var cur = settings(tenantId);
        boolean h = holdHigh != null ? holdHigh : (Boolean) cur.get("holdHighRisk");
        boolean m = holdMedium != null ? holdMedium : (Boolean) cur.get("holdMediumRisk");
        jdbc.sql("INSERT INTO commerce.cod_settings (tenant_id, hold_high_risk, hold_medium_risk) VALUES (:t, :h, :m) ON CONFLICT (tenant_id) DO UPDATE SET hold_high_risk = :h, hold_medium_risk = :m, updated_at = now()")
                .param("t", tenantId).param("h", h).param("m", m).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "cod_guard", null, null);
        return settings(tenantId);
    }

    /** Whether an order with this level must be confirmed by the owner before it is prepared. */
    public boolean mustHold(UUID tenantId, String level) {
        var s = settings(tenantId);
        return ("HIGH".equals(level) && (Boolean) s.get("holdHighRisk")) || ("MEDIUM".equals(level) && (Boolean) s.get("holdMediumRisk"));
    }

    /** What the guard did in the last 30 days, in numbers the owner understands. */
    public Map<String, Object> summary(UUID tenantId) {
        var r = jdbc.sql("""
                SELECT count(*) FILTER (WHERE risk_level = 'HIGH') AS high, count(*) FILTER (WHERE risk_level = 'MEDIUM') AS medium, count(*) FILTER (WHERE risk_level = 'LOW') AS low,
                       count(*) FILTER (WHERE risk_level = 'HIGH' AND status IN ('CANCELLED','RETURNED')) AS high_lost,
                       count(*) FILTER (WHERE risk_level = 'HIGH' AND status = 'ARRIVED') AS high_arrived,
                       coalesce(sum(total_minor) FILTER (WHERE risk_level = 'HIGH' AND status = 'CANCELLED' AND shipped_at IS NULL), 0) AS saved_minor,
                       count(*) FILTER (WHERE needs_confirmation AND confirmed_at IS NULL AND status = 'REQUESTED') AS waiting
                FROM commerce.orders WHERE tenant_id = :t AND created_at > now() - interval '30 days'
                """).param("t", tenantId).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        m.put("settings", settings(tenantId));
        return m;
    }
}
