package com.platform.affiliate;

import com.platform.commerce.OrderEvents;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop's affiliate (marketer) program. The platform records who brought an order and what the shop owes; it never holds or moves the money
 * (the shop pays its marketers itself and reports the payment here).
 *
 * Rules: the commission is on the products after discounts (shipping excluded); the rate that applied is copied onto the commission, so later
 * rate changes never touch old orders; an order earns at most one commission; a commission starts PENDING, becomes APPROVED after the order
 * arrived and the hold period passed, REVERSED when the order is cancelled / returned / refunded, and PAID when the shop reports the payment.
 * Every change is an append-only ledger line. Nobody earns on their own order (the same phone as the partner's).
 */
@Service
public class AffiliateService {
    private static final Logger log = LoggerFactory.getLogger(AffiliateService.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public AffiliateService(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- program ----------------------------------------------------------------------------------------------------------------

    public Map<String, Object> program(UUID t) {
        return jdbc.sql("SELECT enabled, percent, window_days, hold_days, min_payout_minor, terms FROM affiliate.programs WHERE tenant_id = :t").param("t", t)
                .query().listOfRows().stream().findFirst().map(Rows::camel)
                .orElseGet(() -> new LinkedHashMap<>(Map.of("enabled", false, "percent", 5, "windowDays", 30, "holdDays", 14, "minPayoutMinor", 0, "terms", "")));
    }

    public Map<String, Object> saveProgram(UUID t, boolean enabled, double percent, int windowDays, int holdDays, long minPayoutMinor, String terms) {
        if (percent < 0 || percent > 50) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.percent", "The commission must be between 0% and 50%");
        if (windowDays < 1 || windowDays > 365) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.window", "The attribution window is 1 to 365 days");
        if (holdDays < 0 || holdDays > 90) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.hold", "The hold period is 0 to 90 days");
        jdbc.sql("""
                INSERT INTO affiliate.programs (tenant_id, enabled, percent, window_days, hold_days, min_payout_minor, terms, updated_at)
                VALUES (:t, :e, :p, :w, :h, :m, :terms, now())
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = :e, percent = :p, window_days = :w, hold_days = :h, min_payout_minor = :m, terms = :terms, updated_at = now()
                """).param("t", t).param("e", enabled).param("p", percent).param("w", windowDays).param("h", holdDays).param("m", Math.max(0, minPayoutMinor)).param("terms", terms == null ? "" : terms.strip()).update();
        return program(t);
    }

    // ---- partners ---------------------------------------------------------------------------------------------------------------

    public Map<String, Object> addPartner(UUID t, String name, String phone, Double percent) {
        if (name == null || name.isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.name", "Write the marketer's name");
        if (percent != null && (percent < 0 || percent > 50)) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.percent", "The commission must be between 0% and 50%");
        String code = null;
        for (int i = 0; i < 20 && code == null; i++) {
            String c = newCode();
            if (jdbc.sql("SELECT count(*) FROM affiliate.partners WHERE tenant_id = :t AND code = :c").param("t", t).param("c", c).query(Long.class).single() == 0) code = c;
        }
        if (code == null) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.code", "Try again");
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO affiliate.partners (id, tenant_id, name, phone, code, percent) VALUES (:id, :t, :n, :p, :c, :pc)")
                .param("id", id).param("t", t).param("n", name.strip()).param("p", phone == null || phone.isBlank() ? null : phone.strip()).param("c", code).param("pc", percent).update();
        return partner(t, id);
    }

    public Map<String, Object> updatePartner(UUID t, UUID id, String status, Double percent, boolean clearPercent) {
        partner(t, id);
        if (status != null && !status.equals("ACTIVE") && !status.equals("PAUSED")) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.status", "Status is ACTIVE or PAUSED");
        if (percent != null && (percent < 0 || percent > 50)) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.percent", "The commission must be between 0% and 50%");
        if (status != null) jdbc.sql("UPDATE affiliate.partners SET status = :s WHERE id = :id AND tenant_id = :t").param("s", status).param("id", id).param("t", t).update();
        if (clearPercent) jdbc.sql("UPDATE affiliate.partners SET percent = NULL WHERE id = :id AND tenant_id = :t").param("id", id).param("t", t).update();
        else if (percent != null) jdbc.sql("UPDATE affiliate.partners SET percent = :p WHERE id = :id AND tenant_id = :t").param("p", percent).param("id", id).param("t", t).update();
        return partner(t, id);
    }

    public Map<String, Object> partner(UUID t, UUID id) {
        return jdbc.sql("SELECT id, name, phone, code, percent, status, created_at FROM affiliate.partners WHERE id = :id AND tenant_id = :t").param("id", id).param("t", t)
                .query().listOfRows().stream().findFirst().map(Rows::camel).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Marketer not found"));
    }

    /** Every marketer with what they brought: visits, orders, and the commission by state. */
    public List<Map<String, Object>> partners(UUID t) {
        return Rows.camel(jdbc.sql("""
                SELECT p.id, p.name, p.phone, p.code, p.percent, p.status, p.created_at,
                       (SELECT count(*) FROM affiliate.visits v WHERE v.partner_id = p.id) AS visits,
                       count(c.id) FILTER (WHERE c.status <> 'REVERSED') AS orders,
                       coalesce(sum(c.base_minor) FILTER (WHERE c.status <> 'REVERSED'), 0) AS sales_minor,
                       coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PENDING'), 0) AS pending_minor,
                       coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'APPROVED'), 0) AS owed_minor,
                       coalesce(sum(c.amount_minor) FILTER (WHERE c.status = 'PAID'), 0) AS paid_minor
                FROM affiliate.partners p LEFT JOIN affiliate.commissions c ON c.partner_id = p.id
                WHERE p.tenant_id = :t GROUP BY p.id ORDER BY p.created_at DESC
                """).param("t", t).query().listOfRows());
    }

    public List<Map<String, Object>> commissions(UUID t, String status, UUID partnerId, int limit) {
        return Rows.camel(jdbc.sql("""
                SELECT c.id, c.order_number, c.base_minor, c.percent, c.amount_minor, c.status, c.hold_until, c.created_at, p.name AS partner_name, p.code
                FROM affiliate.commissions c JOIN affiliate.partners p ON p.id = c.partner_id
                WHERE c.tenant_id = :t AND (CAST(:s AS text) IS NULL OR c.status = :s) AND (CAST(:p AS uuid) IS NULL OR c.partner_id = :p)
                ORDER BY c.created_at DESC LIMIT :l
                """).param("t", t).param("s", status == null || status.isBlank() ? null : status).param("p", partnerId).param("l", Math.max(1, Math.min(limit, 500))).query().listOfRows());
    }

    public Map<String, Object> summary(UUID t) {
        var row = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status <> 'REVERSED') AS orders,
                       coalesce(sum(base_minor) FILTER (WHERE status <> 'REVERSED'), 0) AS sales_minor,
                       coalesce(sum(amount_minor) FILTER (WHERE status = 'PENDING'), 0) AS pending_minor,
                       coalesce(sum(amount_minor) FILTER (WHERE status = 'APPROVED'), 0) AS owed_minor,
                       coalesce(sum(amount_minor) FILTER (WHERE status = 'PAID'), 0) AS paid_minor,
                       coalesce(sum(amount_minor) FILTER (WHERE status = 'REVERSED'), 0) AS reversed_minor
                FROM affiliate.commissions WHERE tenant_id = :t
                """).param("t", t).query().singleRow();
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(row));
        m.put("partners", jdbc.sql("SELECT count(*) FROM affiliate.partners WHERE tenant_id = :t").param("t", t).query(Long.class).single());
        m.put("visits", jdbc.sql("SELECT count(*) FROM affiliate.visits WHERE tenant_id = :t").param("t", t).query(Long.class).single());
        return m;
    }

    // ---- tracking ---------------------------------------------------------------------------------------------------------------

    /** A visitor arrived with ?ref=CODE: count the visit and tell the page the window to remember the code for, or null when the code is not valid. */
    public Integer visit(UUID t, String code) {
        if (code == null || code.isBlank()) return null;
        var p = jdbc.sql("SELECT p.id FROM affiliate.partners p JOIN affiliate.programs g ON g.tenant_id = p.tenant_id WHERE p.tenant_id = :t AND p.code = :c AND p.status = 'ACTIVE' AND g.enabled")
                .param("t", t).param("c", code.strip().toUpperCase()).query().listOfRows().stream().findFirst();
        if (p.isEmpty()) return null;
        jdbc.sql("INSERT INTO affiliate.visits (tenant_id, partner_id) VALUES (:t, :p)").param("t", t).param("p", p.get().get("id")).update();
        return ((Number) program(t).get("windowDays")).intValue();
    }

    /** Called by the checkout right after the order is saved. Never throws: a problem here must not stop an order. */
    @Transactional
    public void attribute(UUID t, UUID orderId, String ref) {
        if (ref == null || ref.isBlank()) return;
        try {
            var prog = program(t);
            if (!Boolean.TRUE.equals(prog.get("enabled"))) return;
            var partner = jdbc.sql("SELECT id, percent, phone FROM affiliate.partners WHERE tenant_id = :t AND code = :c AND status = 'ACTIVE'")
                    .param("t", t).param("c", ref.strip().toUpperCase()).query().listOfRows().stream().findFirst();
            if (partner.isEmpty()) return;
            var o = jdbc.sql("SELECT order_number, subtotal_minor, discount_minor, customer_phone_snapshot FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", t).query().singleRow();
            String partnerPhone = (String) partner.get().get("phone"), customerPhone = (String) o.get("customer_phone_snapshot");
            if (partnerPhone != null && customerPhone != null && digits(partnerPhone).equals(digits(customerPhone))) return;   // nobody earns on their own order
            long base = Math.max(0, ((Number) o.get("subtotal_minor")).longValue() - ((Number) o.get("discount_minor")).longValue());
            double percent = partner.get().get("percent") != null ? ((Number) partner.get().get("percent")).doubleValue() : ((Number) prog.get("percent")).doubleValue();
            long amount = Math.round(base * percent / 100.0);
            UUID cid = UUID.randomUUID();
            int n = jdbc.sql("INSERT INTO affiliate.commissions (id, tenant_id, partner_id, order_id, order_number, base_minor, percent, amount_minor) VALUES (:id, :t, :p, :o, :n, :b, :pc, :a) ON CONFLICT (order_id) DO NOTHING")
                    .param("id", cid).param("t", t).param("p", partner.get().get("id")).param("o", orderId).param("n", o.get("order_number")).param("b", base).param("pc", percent).param("a", amount).update();
            if (n == 0) return;
            jdbc.sql("UPDATE commerce.orders SET affiliate_code = :c WHERE id = :o").param("c", ref.strip().toUpperCase()).param("o", orderId).update();
            ledger(t, cid, "ACCRUE", amount, "Order " + o.get("order_number") + " at " + percent + "%");
        } catch (RuntimeException e) {
            log.warn("affiliate attribution skipped for order {}: {}", orderId, e.toString());
        }
    }

    @EventListener
    @Transactional
    public void onStatus(OrderEvents.OrderStatusChanged e) {
        var c = jdbc.sql("SELECT id, status, amount_minor FROM affiliate.commissions WHERE order_id = :o").param("o", e.orderId()).query().listOfRows().stream().findFirst();
        if (c.isEmpty()) return;
        String status = (String) c.get().get("status"), now = e.newStatus();
        UUID id = (UUID) c.get().get("id");
        long amount = ((Number) c.get().get("amount_minor")).longValue();
        if (status.equals("PENDING") && "ARRIVED".equals(now)) {
            int hold = ((Number) program(e.tenantId()).get("holdDays")).intValue();
            jdbc.sql("UPDATE affiliate.commissions SET hold_until = now() + make_interval(days => :h), updated_at = now() WHERE id = :id").param("h", hold).param("id", id).update();
        } else if (("CANCELLED".equals(now) || "RETURNED".equals(now) || "REFUNDED".equals(now)) && (status.equals("PENDING") || status.equals("APPROVED"))) {
            jdbc.sql("UPDATE affiliate.commissions SET status = 'REVERSED', updated_at = now() WHERE id = :id").param("id", id).update();
            ledger(e.tenantId(), id, "REVERSE", -amount, "Order " + e.orderNumber() + " became " + now);
        }
    }

    /** Pending commissions whose order arrived and whose hold period passed become approved. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 240_000)
    @Transactional
    public void approveDue() {
        for (var row : jdbc.sql("UPDATE affiliate.commissions SET status = 'APPROVED', updated_at = now() WHERE status = 'PENDING' AND hold_until IS NOT NULL AND hold_until <= now() RETURNING id, tenant_id, amount_minor, order_number").query().listOfRows())
            ledger((UUID) row.get("tenant_id"), (UUID) row.get("id"), "APPROVE", ((Number) row.get("amount_minor")).longValue(), "Order " + row.get("order_number") + " passed the hold period");
    }

    /** The shop reports it paid a marketer everything approved so far (never less than the program's minimum). Returns the amount recorded. */
    @Transactional
    public long recordPayout(UUID t, UUID partnerId) {
        partner(t, partnerId);
        long min = ((Number) program(t).get("minPayoutMinor")).longValue();
        var due = jdbc.sql("SELECT id, amount_minor FROM affiliate.commissions WHERE tenant_id = :t AND partner_id = :p AND status = 'APPROVED' FOR UPDATE").param("t", t).param("p", partnerId).query().listOfRows();
        long total = due.stream().mapToLong(r -> ((Number) r.get("amount_minor")).longValue()).sum();
        if (due.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.nothingDue", "There is nothing approved to pay");
        if (total < min) throw new BusinessException(HttpStatus.CONFLICT, "affiliate.belowMinimum", "The approved amount is below the minimum payout");
        for (var r : due) {
            jdbc.sql("UPDATE affiliate.commissions SET status = 'PAID', updated_at = now() WHERE id = :id").param("id", r.get("id")).update();
            ledger(t, (UUID) r.get("id"), "PAYOUT", -((Number) r.get("amount_minor")).longValue(), "The shop reported the payment");
        }
        return total;
    }

    private void ledger(UUID t, UUID commissionId, String kind, long amount, String note) {
        jdbc.sql("INSERT INTO affiliate.ledger (tenant_id, commission_id, kind, amount_minor, note) VALUES (:t, :c, :k, :a, :n)").param("t", t).param("c", commissionId).param("k", kind).param("a", amount).param("n", note).update();
    }

    private static String digits(String s) { String d = s.replaceAll("\\D", ""); return d.length() > 10 ? d.substring(d.length() - 10) : d; }

    private static String newCode() {
        StringBuilder b = new StringBuilder(8);
        for (int i = 0; i < 8; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return b.toString();
    }
}
