package com.platform.affiliate;

import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The platform admin's view of the whole program: switches, marketers, shops, payout monitoring and the audit trail. All lists are searched, filtered and paged in the database. */
@Service
public class AffiliateAdmin {
    private final JdbcClient jdbc;
    private final AffiliateAudit audit;

    public AffiliateAdmin(JdbcClient jdbc, AffiliateAudit audit) { this.jdbc = jdbc; this.audit = audit; }

    private static String like(String q) { return q == null || q.isBlank() ? null : "%" + q.strip() + "%"; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
    private static Map<String, Object> page(Object data, long total, int page, int size) { return Map.of("data", data, "total", total, "page", page, "size", size); }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(jdbc.sql("""
                SELECT (SELECT count(*) FROM affiliate.accounts) AS marketers, (SELECT count(*) FROM affiliate.accounts WHERE status = 'SUSPENDED') AS suspended,
                       (SELECT count(*) FROM affiliate.programs WHERE enabled) AS shops_enabled, (SELECT count(*) FROM affiliate.programs WHERE enabled AND listed) AS shops_listed,
                       (SELECT count(*) FROM affiliate.partners WHERE status = 'PENDING') AS applications_waiting,
                       (SELECT count(*) FROM affiliate.commissions WHERE status <> 'REVERSED') AS orders,
                       (SELECT coalesce(sum(base_minor), 0) FROM affiliate.commissions WHERE status <> 'REVERSED') AS sales_minor,
                       (SELECT coalesce(sum(amount_minor), 0) FROM affiliate.commissions WHERE status = 'PENDING') AS pending_minor,
                       (SELECT coalesce(sum(amount_minor), 0) FROM affiliate.commissions WHERE status = 'APPROVED') AS approved_minor,
                       (SELECT coalesce(sum(amount_minor), 0) FROM affiliate.commissions WHERE status = 'PAID') AS paid_minor,
                       (SELECT coalesce(sum(amount_minor), 0) FROM affiliate.commissions WHERE status = 'REVERSED') AS reversed_minor,
                       (SELECT count(*) FROM affiliate.payout_requests WHERE status = 'REQUESTED') AS payouts_open,
                       (SELECT coalesce(sum(amount_minor), 0) FROM affiliate.payout_requests WHERE status = 'REQUESTED') AS payouts_open_minor,
                       (SELECT count(*) FROM affiliate.fraud_flags WHERE status = 'OPEN') AS flags_open,
                       (SELECT count(*) FROM affiliate.disputes WHERE status = 'OPEN') AS disputes_open
                """).query().singleRow()));
        m.put("settings", settings());
        return m;
    }

    public Map<String, Object> settings() { return Rows.camel(jdbc.sql("SELECT registration_open, marketplace_open, max_percent FROM affiliate.settings WHERE id = 1").query().singleRow()); }

    public Map<String, Object> saveSettings(UUID admin, Boolean registrationOpen, Boolean marketplaceOpen, Double maxPercent) {
        if (maxPercent != null && (maxPercent < 1 || maxPercent > 50)) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.percent", "The cap is 1% to 50%");
        jdbc.sql("UPDATE affiliate.settings SET registration_open = coalesce(:r, registration_open), marketplace_open = coalesce(:m, marketplace_open), max_percent = coalesce(:p, max_percent), updated_at = now() WHERE id = 1")
                .param("r", registrationOpen).param("m", marketplaceOpen).param("p", maxPercent).update();
        audit.log("PLATFORM", admin, null, null, "SETTINGS", "registration=" + registrationOpen + " marketplace=" + marketplaceOpen + " cap=" + maxPercent);
        return settings();
    }

    public Map<String, Object> marketers(String q, String status, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        String w = " WHERE (CAST(:q AS text) IS NULL OR a.name ILIKE :q OR a.phone ILIKE :q) AND (CAST(:st AS text) IS NULL OR a.status = :st)";
        var rows = Rows.camel(jdbc.sql("""
                SELECT a.id, a.name, a.phone, a.city, a.status, a.created_at, a.last_login_at,
                       (SELECT count(*) FROM affiliate.partners p WHERE p.account_id = a.id AND p.status = 'ACTIVE') AS shops,
                       coalesce((SELECT sum(c.amount_minor) FROM affiliate.commissions c JOIN affiliate.partners p ON p.id = c.partner_id WHERE p.account_id = a.id AND c.status IN ('PENDING','APPROVED')), 0) AS open_minor,
                       coalesce((SELECT sum(c.amount_minor) FROM affiliate.commissions c JOIN affiliate.partners p ON p.id = c.partner_id WHERE p.account_id = a.id AND c.status = 'PAID'), 0) AS paid_minor,
                       (SELECT count(*) FROM affiliate.fraud_flags f JOIN affiliate.partners p ON p.id = f.partner_id WHERE p.account_id = a.id AND f.status = 'OPEN') AS open_flags
                FROM affiliate.accounts a""" + w + " ORDER BY a.created_at DESC LIMIT :l OFFSET :o").param("q", like(q)).param("st", blank(status)).param("l", s).param("o", off).query().listOfRows());
        return page(rows, jdbc.sql("SELECT count(*) FROM affiliate.accounts a" + w).param("q", like(q)).param("st", blank(status)).query(Long.class).single(), page, s);
    }

    public void setAccountStatus(UUID admin, UUID account, String status) {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw new BusinessException(HttpStatus.BAD_REQUEST, "affiliate.status", "Status is ACTIVE or SUSPENDED");
        if (jdbc.sql("UPDATE affiliate.accounts SET status = :s WHERE id = :a").param("s", status).param("a", account).update() == 0) throw new BusinessException(HttpStatus.NOT_FOUND, "affiliate.notFound", "Marketer not found");
        if ("SUSPENDED".equals(status)) jdbc.sql("UPDATE affiliate.sessions SET revoked_at = now() WHERE account_id = :a AND revoked_at IS NULL").param("a", account).update();
        audit.log("PLATFORM", admin, null, null, "ACCOUNT_" + status, account.toString());
    }

    public Map<String, Object> shops(String q, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        String w = " WHERE (CAST(:q AS text) IS NULL OR t.name ILIKE :q OR t.slug ILIKE :q)";
        var rows = Rows.camel(jdbc.sql("""
                SELECT t.id, t.name, t.slug, g.enabled, g.listed, g.percent, g.auto_approve,
                       (SELECT count(*) FROM affiliate.partners p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS marketers,
                       (SELECT count(*) FROM affiliate.commissions c WHERE c.tenant_id = t.id AND c.status <> 'REVERSED') AS orders,
                       coalesce((SELECT sum(c.amount_minor) FROM affiliate.commissions c WHERE c.tenant_id = t.id AND c.status = 'APPROVED'), 0) AS owed_minor,
                       coalesce((SELECT sum(c.amount_minor) FROM affiliate.commissions c WHERE c.tenant_id = t.id AND c.status = 'PAID'), 0) AS paid_minor,
                       (SELECT count(*) FROM affiliate.payout_requests r WHERE r.tenant_id = t.id AND r.status = 'REQUESTED') AS payouts_open
                FROM affiliate.programs g JOIN core.tenants t ON t.id = g.tenant_id""" + w + " ORDER BY g.enabled DESC, t.name LIMIT :l OFFSET :o").param("q", like(q)).param("l", s).param("o", off).query().listOfRows());
        return page(rows, jdbc.sql("SELECT count(*) FROM affiliate.programs g JOIN core.tenants t ON t.id = g.tenant_id" + w).param("q", like(q)).query(Long.class).single(), page, s);
    }

    /** Every payout request on the platform, with its age, so requests a shop leaves unanswered are visible. */
    public Map<String, Object> payouts(String status, String q, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        String w = " WHERE (CAST(:st AS text) IS NULL OR r.status = :st) AND (CAST(:q AS text) IS NULL OR t.name ILIKE :q OR p.name ILIKE :q)";
        var rows = Rows.camel(jdbc.sql("SELECT r.id, r.amount_minor, r.status, r.requested_at, r.decided_at, r.evidence, r.decision_note, t.name AS shop, p.name AS marketer, extract(day FROM now() - r.requested_at)::int AS age_days FROM affiliate.payout_requests r JOIN affiliate.partners p ON p.id = r.partner_id JOIN core.tenants t ON t.id = r.tenant_id" + w + " ORDER BY r.requested_at DESC LIMIT :l OFFSET :o")
                .param("st", blank(status)).param("q", like(q)).param("l", s).param("o", off).query().listOfRows());
        return page(rows, jdbc.sql("SELECT count(*) FROM affiliate.payout_requests r JOIN affiliate.partners p ON p.id = r.partner_id JOIN core.tenants t ON t.id = r.tenant_id" + w).param("st", blank(status)).param("q", like(q)).query(Long.class).single(), page, s);
    }

    public Map<String, Object> disputes(String status, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        var rows = Rows.camel(jdbc.sql("SELECT d.id, d.opened_by, d.reason, d.status, d.resolution, d.created_at, t.name AS shop, p.name AS marketer FROM affiliate.disputes d JOIN affiliate.partners p ON p.id = d.partner_id JOIN core.tenants t ON t.id = d.tenant_id WHERE (CAST(:st AS text) IS NULL OR d.status = :st) ORDER BY d.created_at DESC LIMIT :l OFFSET :o")
                .param("st", blank(status)).param("l", s).param("o", off).query().listOfRows());
        return page(rows, jdbc.sql("SELECT count(*) FROM affiliate.disputes d WHERE (CAST(:st AS text) IS NULL OR d.status = :st)").param("st", blank(status)).query(Long.class).single(), page, s);
    }

    public Map<String, Object> auditLog(String actor, String action, String q, int page, int size) {
        int s = Math.max(1, Math.min(size, 100)), off = Math.max(0, page) * s;
        String w = " WHERE (CAST(:ac AS text) IS NULL OR a.actor_type = :ac) AND (CAST(:act AS text) IS NULL OR a.action = :act) AND (CAST(:q AS text) IS NULL OR a.detail ILIKE :q OR t.slug ILIKE :q OR p.name ILIKE :q)";
        var rows = Rows.camel(jdbc.sql("SELECT a.id, a.actor_type, a.action, a.detail, a.created_at, t.slug AS shop, p.name AS marketer FROM affiliate.audit a LEFT JOIN core.tenants t ON t.id = a.tenant_id LEFT JOIN affiliate.partners p ON p.id = a.partner_id" + w + " ORDER BY a.id DESC LIMIT :l OFFSET :o")
                .param("ac", blank(actor)).param("act", blank(action)).param("q", like(q)).param("l", s).param("o", off).query().listOfRows());
        return page(rows, jdbc.sql("SELECT count(*) FROM affiliate.audit a LEFT JOIN core.tenants t ON t.id = a.tenant_id LEFT JOIN affiliate.partners p ON p.id = a.partner_id" + w).param("ac", blank(actor)).param("act", blank(action)).param("q", like(q)).query(Long.class).single(), page, s);
    }
}
