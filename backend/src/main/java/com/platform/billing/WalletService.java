package com.platform.billing;

import com.platform.audit.AuditService;
import com.platform.notifications.NotificationService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prepaid WhatsApp credit. Amounts are in piasters (100 = 1 EGP). The balance can never go below zero: a debit is one conditional UPDATE, so two
 * jobs or two requests can never spend the same money, and every change is written to a ledger.
 */
@Service
public class WalletService {
    public static final long MIN_TOPUP_MINOR = 1_000;        // 10 EGP
    public static final long MAX_TOPUP_MINOR = 5_000_000;    // 50,000 EGP

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditService audit;

    public WalletService(JdbcClient jdbc, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    public long balance(UUID tenantId) {
        return jdbc.sql("SELECT balance_minor FROM billing.wallets WHERE tenant_id = :t").param("t", tenantId).query(Long.class).optional().orElse(0L);
    }

    /** Adds money (a top-up, a refund or an admin adjustment). Returns the new balance. */
    @Transactional
    public long credit(UUID tenantId, long amountMinor, String kind, String ref, String note, UUID actor) {
        if (amountMinor <= 0) throw BusinessException.badRequest("VALIDATION_ERROR", "The amount must be positive");
        long after = jdbc.sql("""
                INSERT INTO billing.wallets (tenant_id, balance_minor) VALUES (:t, :a)
                ON CONFLICT (tenant_id) DO UPDATE SET balance_minor = billing.wallets.balance_minor + :a RETURNING balance_minor
                """).param("t", tenantId).param("a", amountMinor).query(Long.class).single();
        entry(tenantId, kind, amountMinor, after, ref, note, actor);
        return after;
    }

    /** Spends money if the balance covers it; false (and nothing changes) when it does not. */
    @Transactional
    public boolean tryDebit(UUID tenantId, long amountMinor, String ref, String note) {
        Long after = jdbc.sql("UPDATE billing.wallets SET balance_minor = balance_minor - :a WHERE tenant_id = :t AND balance_minor >= :a RETURNING balance_minor")
                .param("t", tenantId).param("a", amountMinor).query(Long.class).optional().orElse(null);
        if (after == null) return false;
        entry(tenantId, "MESSAGE", -amountMinor, after, ref, note, null);
        return true;
    }

    private void entry(UUID tenantId, String kind, long amount, long after, String ref, String note, UUID actor) {
        jdbc.sql("INSERT INTO billing.wallet_entries (tenant_id, kind, amount_minor, balance_after_minor, ref, note, created_by) VALUES (:t, :k, :a, :b, :r, :n, :u)")
                .param("t", tenantId).param("k", kind).param("a", amount).param("b", after).param("r", ref).param("n", note).param("u", actor).update();
    }

    public List<Map<String, Object>> entries(UUID tenantId, int limit) {
        return Rows.camel(jdbc.sql("SELECT kind, amount_minor, balance_after_minor, ref, note, created_at FROM billing.wallet_entries WHERE tenant_id = :t ORDER BY created_at DESC LIMIT :l")
                .param("t", tenantId).param("l", Math.min(limit, 200)).query().listOfRows());
    }

    // ---- top-up requests (the shop asks, an admin approves after receiving the money) --------------------------

    public Map<String, Object> requestTopup(UUID tenantId, UUID userId, long amountMinor, String phone, String note) {
        if (amountMinor < MIN_TOPUP_MINOR || amountMinor > MAX_TOPUP_MINOR) throw BusinessException.badRequest("VALIDATION_ERROR", "The amount must be between 10 and 50,000 EGP");
        if (jdbc.sql("SELECT count(*) FROM billing.topup_requests WHERE tenant_id = :t AND status = 'PENDING'").param("t", tenantId).query(Long.class).single() >= 3)
            throw BusinessException.conflict("TOO_MANY_PENDING", "You already have pending top-up requests; wait for them to be handled");
        UUID id = jdbc.sql("INSERT INTO billing.topup_requests (tenant_id, amount_minor, phone, note, created_by) VALUES (:t, :a, :p, :n, :u) RETURNING id")
                .param("t", tenantId).param("a", amountMinor).param("p", phone == null || phone.isBlank() ? null : phone.trim()).param("n", note == null || note.isBlank() ? null : note.trim()).param("u", userId)
                .query(UUID.class).single();
        notifyAdmins(tenantId, amountMinor);
        return topup(id);
    }

    public Map<String, Object> topup(UUID id) {
        return Rows.camel(jdbc.sql("SELECT r.id, r.tenant_id, t.name AS tenant_name, r.amount_minor, r.phone, r.note, r.status, r.admin_note, r.handled_at, r.created_at FROM billing.topup_requests r JOIN core.tenants t ON t.id = r.tenant_id WHERE r.id = :i")
                .param("i", id).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Request not found")));
    }

    public List<Map<String, Object>> topupsOf(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, amount_minor, status, admin_note, created_at, handled_at FROM billing.topup_requests WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 20").param("t", tenantId).query().listOfRows());
    }

    public Map<String, Object> adminTopups(String status, Page page) {
        String st = status == null || status.isBlank() ? null : status;
        long total = jdbc.sql("SELECT count(*) FROM billing.topup_requests WHERE (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))").param("s", st).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT r.id, r.tenant_id, t.name AS tenant_name, r.amount_minor, r.phone, r.note, r.status, r.admin_note, r.handled_at, r.created_at,
                       coalesce((SELECT balance_minor FROM billing.wallets w WHERE w.tenant_id = r.tenant_id), 0) AS balance_minor
                FROM billing.topup_requests r JOIN core.tenants t ON t.id = r.tenant_id
                WHERE (CAST(:s AS varchar) IS NULL OR r.status = CAST(:s AS varchar)) ORDER BY (r.status = 'PENDING') DESC, r.created_at DESC LIMIT :l OFFSET :o
                """).param("s", st).param("l", page.pageSize()).param("o", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    /** Approving credits the shop exactly once: the request row is locked and must still be PENDING. */
    @Transactional
    public Map<String, Object> handleTopup(UUID actor, UUID id, boolean approve, String adminNote) {
        var r = jdbc.sql("SELECT tenant_id, amount_minor, status FROM billing.topup_requests WHERE id = :i FOR UPDATE").param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Request not found"));
        if (!"PENDING".equals(r.get("status"))) throw BusinessException.conflict("ALREADY_HANDLED", "This request was already handled");
        UUID tenantId = (UUID) r.get("tenant_id");
        long amount = ((Number) r.get("amount_minor")).longValue();
        jdbc.sql("UPDATE billing.topup_requests SET status = :s, admin_note = :n, handled_by = :a, handled_at = now() WHERE id = :i")
                .param("s", approve ? "APPROVED" : "REJECTED").param("n", adminNote == null || adminNote.isBlank() ? null : adminNote.trim()).param("a", actor).param("i", id).update();
        if (approve) credit(tenantId, amount, "TOPUP", id.toString(), "Top-up approved", actor);
        audit.record(actor, tenantId, approve ? "WALLET_TOPUP_APPROVED" : "WALLET_TOPUP_REJECTED", "topup_request", id, null);
        return topup(id);
    }

    /** An admin corrects a balance (positive adds, negative removes but never below zero). */
    @Transactional
    public long adjust(UUID actor, UUID tenantId, long amountMinor, String note) {
        if (amountMinor == 0) throw BusinessException.badRequest("VALIDATION_ERROR", "The amount cannot be zero");
        if (amountMinor > 0) return credit(tenantId, amountMinor, "ADJUST", null, note, actor);
        Long after = jdbc.sql("UPDATE billing.wallets SET balance_minor = balance_minor + :a WHERE tenant_id = :t AND balance_minor + :a >= 0 RETURNING balance_minor")
                .param("t", tenantId).param("a", amountMinor).query(Long.class).optional().orElse(null);
        if (after == null) throw BusinessException.badRequest("INSUFFICIENT_BALANCE", "The balance is lower than that");
        entry(tenantId, "ADJUST", amountMinor, after, null, note, actor);
        audit.record(actor, tenantId, "WALLET_ADJUSTED", "wallet", tenantId, null);
        return after;
    }

    public List<Map<String, Object>> balances(String q) {
        return Rows.camel(jdbc.sql("""
                SELECT t.id AS tenant_id, t.name AS tenant_name, w.balance_minor FROM billing.wallets w JOIN core.tenants t ON t.id = w.tenant_id
                WHERE (CAST(:q AS varchar) IS NULL OR t.name ILIKE '%' || CAST(:q AS varchar) || '%' OR t.slug ILIKE '%' || CAST(:q AS varchar) || '%') ORDER BY t.name LIMIT 100
                """).param("q", q == null || q.isBlank() ? null : q.trim()).query().listOfRows());
    }

    /** Tells the shop's owner (in-app + phone push). */
    public void notifyOwner(UUID tenantId, String title, String body, String type) {
        var owners = jdbc.sql("""
                SELECT DISTINCT m.user_id FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id
                WHERE m.tenant_id = :t AND r.code = 'STORE_OWNER'
                """).param("t", tenantId).query(UUID.class).list();
        for (UUID u : owners) {
            notifications.notify(u, tenantId, type, title, body, "{\"type\":\"" + type + "\"}", false);
            notifications.push(u, tenantId, title, body, "{\"type\":\"" + type + "\"}");
        }
        if (!owners.isEmpty()) notifications.dispatchSoon();
    }

    private void notifyAdmins(UUID tenantId, long amountMinor) {
        String name = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("");
        String title = "طلب شحن رصيد واتساب";
        String body = name + " - " + (amountMinor / 100) + " EGP";
        var admins = jdbc.sql("""
                SELECT DISTINCT pr.user_id FROM core.user_platform_roles pr JOIN core.role_permissions rp ON rp.role_id = pr.role_id
                JOIN core.permissions p ON p.id = rp.permission_id JOIN core.users u ON u.id = pr.user_id WHERE p.code = 'platform.tenant.manage' AND u.status = 'ACTIVE'
                """).query(UUID.class).list();
        for (UUID u : admins) {
            notifications.notify(u, null, "WALLET_TOPUP", title, body, "{\"type\":\"WALLET_TOPUP\",\"tenantId\":\"" + tenantId + "\"}", false);
            notifications.push(u, null, title, body, "{\"type\":\"WALLET_TOPUP\",\"tenantId\":\"" + tenantId + "\"}");
        }
        if (!admins.isEmpty()) notifications.dispatchSoon();
    }
}
