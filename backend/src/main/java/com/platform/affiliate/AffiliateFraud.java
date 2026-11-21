package com.platform.affiliate;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Fraud signals for the platform admin. Nothing here punishes anybody: a rule that fires writes ONE open flag (per kind, marketer and order) that the admin dismisses,
 * confirms or acts on (suspend the marketer). Thresholds are constants on purpose, in one place, so they are easy to tune.
 *  SELF_REFERRAL  the marketer's own phone on an order from their link (the order earns nothing)
 *  DUPLICATE_CUSTOMER  the same customer credited 3+ times to one marketer in 30 days
 *  BURST  10+ credited orders for one marketer within an hour
 *  SHARED_ADDRESS  3+ different phones delivering to one address through one marketer in 30 days
 *  HIGH_REVERSAL  half or more of a marketer's 5+ commissions reversed
 *  CLICK_FLOOD  300+ visits on one marketer's links within an hour
 */
@Service
public class AffiliateFraud {
    static final int DUP_CUSTOMER = 3, BURST_ORDERS = 10, SHARED_PHONES = 3, REVERSAL_MIN = 5, CLICK_FLOOD = 300;
    static final double REVERSAL_RATIO = 0.5;
    private final JdbcClient jdbc;
    private final AffiliateAudit audit;

    public AffiliateFraud(JdbcClient jdbc, AffiliateAudit audit) { this.jdbc = jdbc; this.audit = audit; }

    public void flag(UUID tenant, UUID partner, UUID commission, String kind, String severity, String detail) {
        jdbc.sql("""
                INSERT INTO affiliate.fraud_flags (tenant_id, partner_id, commission_id, kind, severity, detail)
                SELECT CAST(:t AS uuid), CAST(:p AS uuid), CAST(:c AS uuid), CAST(:k AS varchar), CAST(:s AS varchar), CAST(:d AS varchar) WHERE NOT EXISTS (SELECT 1 FROM affiliate.fraud_flags WHERE status = 'OPEN' AND kind = CAST(:k AS varchar) AND partner_id = CAST(:p AS uuid) AND coalesce(commission_id, '00000000-0000-0000-0000-000000000000') = coalesce(CAST(:c AS uuid), CAST('00000000-0000-0000-0000-000000000000' AS uuid)))
                """).param("t", tenant).param("p", partner).param("c", commission).param("k", kind).param("s", severity).param("d", detail).update();
    }

    /** Right after a commission is created. */
    public void afterAttribution(UUID tenant, UUID partner, UUID commission, UUID orderId) {
        var o = jdbc.sql("SELECT customer_phone_snapshot AS phone, lower(coalesce(shipping_address_snapshot ->> 'addressLine1', '')) AS addr FROM commerce.orders WHERE id = :o").param("o", orderId).query().singleRow();
        String phone = (String) o.get("phone"), addr = ((String) o.get("addr")).strip();
        if (phone != null) {
            long same = jdbc.sql("SELECT count(*) FROM affiliate.commissions c JOIN commerce.orders x ON x.id = c.order_id WHERE c.partner_id = :p AND x.customer_phone_snapshot = :ph AND c.created_at > now() - interval '30 days'").param("p", partner).param("ph", phone).query(Long.class).single();
            if (same >= DUP_CUSTOMER) flag(tenant, partner, commission, "DUPLICATE_CUSTOMER", "MEDIUM", "The same customer was credited " + same + " times in 30 days");
        }
        long burst = jdbc.sql("SELECT count(*) FROM affiliate.commissions WHERE partner_id = :p AND created_at > now() - interval '1 hour'").param("p", partner).query(Long.class).single();
        if (burst >= BURST_ORDERS) flag(tenant, partner, null, "BURST", "HIGH", burst + " credited orders within an hour");
        if (addr.length() >= 8) {
            long phones = jdbc.sql("SELECT count(DISTINCT x.customer_phone_snapshot) FROM affiliate.commissions c JOIN commerce.orders x ON x.id = c.order_id WHERE c.partner_id = :p AND c.created_at > now() - interval '30 days' AND lower(coalesce(x.shipping_address_snapshot ->> 'addressLine1', '')) = :a").param("p", partner).param("a", addr).query(Long.class).single();
            if (phones >= SHARED_PHONES) flag(tenant, partner, commission, "SHARED_ADDRESS", "MEDIUM", phones + " different phones ordered to one address through this marketer");
        }
    }

    /** Hourly scan for the signals that are not about one order. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void scan() {
        for (var r : jdbc.sql("SELECT c.tenant_id, c.partner_id, count(*) AS n, count(*) FILTER (WHERE c.status = 'REVERSED') AS rev FROM affiliate.commissions c GROUP BY c.tenant_id, c.partner_id HAVING count(*) >= :m").param("m", REVERSAL_MIN).query().listOfRows()) {
            long n = ((Number) r.get("n")).longValue(), rev = ((Number) r.get("rev")).longValue();
            if (rev >= n * REVERSAL_RATIO) flag((UUID) r.get("tenant_id"), (UUID) r.get("partner_id"), null, "HIGH_REVERSAL", "MEDIUM", rev + " of " + n + " commissions were reversed");
        }
        for (var r : jdbc.sql("SELECT tenant_id, partner_id, count(*) AS n FROM affiliate.visits WHERE visited_at > now() - interval '1 hour' GROUP BY tenant_id, partner_id HAVING count(*) >= :m").param("m", CLICK_FLOOD).query().listOfRows())
            flag((UUID) r.get("tenant_id"), (UUID) r.get("partner_id"), null, "CLICK_FLOOD", "LOW", r.get("n") + " visits within an hour");
    }

    public Map<String, Object> flags(String status, String kind, String q, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        String where = "WHERE (CAST(:st AS text) IS NULL OR f.status = :st) AND (CAST(:k AS text) IS NULL OR f.kind = :k) AND (CAST(:q AS text) IS NULL OR p.name ILIKE :q OR t.slug ILIKE :q OR f.detail ILIKE :q)";
        String q2 = q == null || q.isBlank() ? null : "%" + q.strip() + "%";
        var rows = Rows.camel(jdbc.sql("SELECT f.id, f.kind, f.severity, f.detail, f.status, f.review_note, f.created_at, f.reviewed_at, f.partner_id, t.slug AS shop, t.name AS shop_name, p.name AS marketer, p.code FROM affiliate.fraud_flags f LEFT JOIN affiliate.partners p ON p.id = f.partner_id LEFT JOIN core.tenants t ON t.id = f.tenant_id " + where + " ORDER BY f.created_at DESC LIMIT :l OFFSET :o")
                .param("st", blank(status)).param("k", blank(kind)).param("q", q2).param("l", s).param("o", off).query().listOfRows());
        long total = jdbc.sql("SELECT count(*) FROM affiliate.fraud_flags f LEFT JOIN affiliate.partners p ON p.id = f.partner_id LEFT JOIN core.tenants t ON t.id = f.tenant_id " + where).param("st", blank(status)).param("k", blank(kind)).param("q", q2).query(Long.class).single();
        return Map.of("data", rows, "total", total, "page", page, "size", s);
    }

    public void review(UUID flagId, String decision, String note, UUID adminId, boolean suspendMarketer) {
        if (!List.of("DISMISSED", "CONFIRMED").contains(decision)) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.decision", "Decision is DISMISSED or CONFIRMED");
        var f = jdbc.sql("SELECT partner_id, tenant_id FROM affiliate.fraud_flags WHERE id = :i AND status = 'OPEN'").param("i", flagId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Open flag not found"));
        jdbc.sql("UPDATE affiliate.fraud_flags SET status = :s, review_note = :n, reviewed_at = now() WHERE id = :i").param("s", decision).param("n", note == null ? null : note.strip()).param("i", flagId).update();
        audit.log("PLATFORM", adminId, (UUID) f.get("tenant_id"), (UUID) f.get("partner_id"), "FLAG_" + decision, note);
        if (suspendMarketer && "CONFIRMED".equals(decision) && f.get("partner_id") != null) {
            var acc = jdbc.sql("SELECT account_id FROM affiliate.partners WHERE id = :p").param("p", f.get("partner_id")).query(UUID.class).optional().orElse(null);
            if (acc != null) {
                jdbc.sql("UPDATE affiliate.accounts SET status = 'SUSPENDED' WHERE id = :a").param("a", acc).update();
                jdbc.sql("UPDATE affiliate.sessions SET revoked_at = now() WHERE account_id = :a AND revoked_at IS NULL").param("a", acc).update();
                audit.log("PLATFORM", adminId, null, null, "ACCOUNT_SUSPENDED", "after a confirmed " + decision + " flag");
            } else jdbc.sql("UPDATE affiliate.partners SET status = 'BLOCKED' WHERE id = :p").param("p", f.get("partner_id")).update();
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
