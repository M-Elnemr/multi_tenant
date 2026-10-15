package com.platform.platform;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Control-plane operations for the platform team (spec 48). It exposes tenant metadata, subscriptions and
 * audit - deliberately never patient or order content, so support/finance staff have no clinical data access (spec 76).
 */
@Service
public class PlatformAdminService {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final com.platform.core.tenant.TenantDirectory directory;
    private final com.platform.billing.BillingService billing;
    private final com.platform.billing.PayRequestService payRequests;

    public PlatformAdminService(JdbcClient jdbc, AuditService audit, com.platform.core.tenant.TenantDirectory directory,
                                com.platform.billing.BillingService billing, com.platform.billing.PayRequestService payRequests) {
        this.billing = billing;
        this.payRequests = payRequests;
        this.directory = directory;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> byType = new LinkedHashMap<>();
        jdbc.sql("SELECT tenant_type, status, count(*) AS c FROM core.tenants GROUP BY tenant_type, status ORDER BY tenant_type, status").query().listOfRows()
                .forEach(r -> ((Map<String, Object>) byType.computeIfAbsent((String) r.get("tenant_type"), k -> new LinkedHashMap<String, Object>())).put((String) r.get("status"), r.get("c")));
        out.put("tenantsByTypeAndStatus", byType);
        out.put("totalTenants", jdbc.sql("SELECT count(*) FROM core.tenants").query(Long.class).single());
        out.put("totalUsers", jdbc.sql("SELECT count(*) FROM core.users").query(Long.class).single());
        out.put("activeSubscriptionsByPlan", Rows.camel(jdbc.sql("""
                SELECT p.code AS plan, s.status, count(*) AS c FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id
                WHERE s.status IN ('TRIALING','ACTIVE','PAST_DUE') GROUP BY p.code, s.status ORDER BY p.code, s.status
                """).query().listOfRows()));
        out.put("monthlyRecurringRevenueMinor", jdbc.sql("SELECT coalesce(sum(p.price_minor), 0) FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id WHERE s.status = 'ACTIVE' AND p.interval = 'MONTH'").query(Long.class).single());
        out.put("trialsEndingIn7Days", jdbc.sql("SELECT count(*) FROM billing.tenant_subscriptions WHERE status = 'TRIALING' AND current_period_end < now() + interval '7 days'").query(Long.class).single());
        out.put("newPayRequests", payRequests.newCount());
        out.put("waitingForRenew", jdbc.sql("SELECT count(*) FROM billing.tenant_subscriptions WHERE status = 'PAST_DUE'").query(Long.class).single());
        out.put("disabled", jdbc.sql("SELECT count(*) FROM core.tenants WHERE status = 'SUSPENDED'").query(Long.class).single());
        out.put("expiringIn7Days", jdbc.sql("SELECT count(*) FROM billing.tenant_subscriptions WHERE status IN ('TRIALING','ACTIVE') AND current_period_end < now() + interval '7 days'").query(Long.class).single());
        out.put("newTenantsLast7Days", jdbc.sql("SELECT count(*) FROM core.tenants WHERE created_at > now() - interval '7 days'").query(Long.class).single());
        return out;
    }

    public Map<String, Object> tenants(String type, String status, String q, String filter, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        String extra = "";
        if ("expiring".equals(filter)) extra = " AND s.status IN ('TRIALING','ACTIVE') AND s.current_period_end < now() + interval '7 days'";
        else if ("waiting".equals(filter)) extra = " AND s.status = 'PAST_DUE'";
        else if ("disabled".equals(filter)) extra = " AND t.status = 'SUSPENDED'";
        else if ("pay".equals(filter)) extra = " AND EXISTS (SELECT 1 FROM billing.pay_requests r WHERE r.tenant_id = t.id AND r.status IN ('NEW','CONTACTED'))";
        String from = """
                 FROM core.tenants t LEFT JOIN LATERAL (SELECT * FROM billing.tenant_subscriptions x WHERE x.tenant_id = t.id ORDER BY x.created_at DESC LIMIT 1) s ON TRUE
                 LEFT JOIN billing.subscription_plans p ON p.id = s.plan_id
                 WHERE (CAST(:ty AS varchar) IS NULL OR t.tenant_type = CAST(:ty AS varchar)) AND (CAST(:st AS varchar) IS NULL OR t.status = CAST(:st AS varchar))
                   AND (CAST(:q AS varchar) IS NULL OR lower(t.name) LIKE CAST(:q AS varchar) OR t.slug LIKE CAST(:q AS varchar)
                        OR EXISTS (SELECT 1 FROM core.user_tenant_memberships m JOIN core.users u ON u.id = m.user_id WHERE m.tenant_id = t.id AND (u.phone LIKE CAST(:q AS varchar) OR lower(u.email) LIKE CAST(:q AS varchar))))
                """ + extra;
        long total = jdbc.sql("SELECT count(*)" + from).param("ty", type).param("st", status).param("q", like).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT t.id, t.slug, t.name, t.tenant_type, t.status, t.created_at, s.status AS subscription_status, p.code AS plan_code, p.name AS plan_name, p.price_minor,
                       s.current_period_start, s.current_period_end,
                       (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary) AS primary_host,
                       (SELECT u.first_name || ' ' || coalesce(u.last_name, '') FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id
                          JOIN core.roles r ON r.id = mr.role_id JOIN core.users u ON u.id = m.user_id WHERE m.tenant_id = t.id AND r.code IN ('STORE_OWNER','CLINIC_OWNER') ORDER BY m.created_at LIMIT 1) AS owner_name,
                       (SELECT u.phone FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id
                          JOIN core.roles r ON r.id = mr.role_id JOIN core.users u ON u.id = m.user_id WHERE m.tenant_id = t.id AND r.code IN ('STORE_OWNER','CLINIC_OWNER') ORDER BY m.created_at LIMIT 1) AS owner_phone,
                       EXISTS (SELECT 1 FROM billing.pay_requests r WHERE r.tenant_id = t.id AND r.status IN ('NEW','CONTACTED')) AS has_pay_request
                """ + from + " ORDER BY t.created_at DESC LIMIT :lim OFFSET :off")
                .param("ty", type).param("st", status).param("q", like).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    public Map<String, Object> tenant(UUID id) {
        var t = jdbc.sql("SELECT id, slug, name, tenant_type, status, default_locale, default_currency, timezone, trial_ends_at, created_at FROM core.tenants WHERE id = :i").param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
        Map<String, Object> out = new LinkedHashMap<>(Rows.camel(t));
        out.put("domains", Rows.camel(jdbc.sql("SELECT host, kind, is_primary, is_verified, ssl_status FROM core.tenant_domains WHERE tenant_id = :t ORDER BY created_at").param("t", id).query().listOfRows()));
        out.put("subscription", Rows.camel(jdbc.sql("""
                SELECT s.status, p.code AS plan_code, p.name AS plan_name, p.price_minor, s.current_period_start, s.current_period_end, s.cancel_at_period_end, s.created_at
                FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t ORDER BY s.created_at DESC LIMIT 1
                """).param("t", id).query().listOfRows().stream().findFirst().orElse(Map.of())));
        out.put("owner", payRequests.ownerContact(id));
        out.put("billingState", billing.status(id));
        out.put("payRequests", payRequests.forTenant(id));
        out.put("events", Rows.camel(jdbc.sql("SELECT event_type, created_at FROM billing.subscription_events WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 15").param("t", id).query().listOfRows()));
        out.put("staffCount", jdbc.sql("SELECT count(DISTINCT m.id) FROM core.user_tenant_memberships m JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id WHERE m.tenant_id = :t AND r.code NOT IN ('CUSTOMER','PATIENT','GUARDIAN')").param("t", id).query(Long.class).single());
        out.put("usage", Rows.camel(jdbc.sql("SELECT metric, period, value FROM billing.usage_counters WHERE tenant_id = :t ORDER BY metric, period DESC").param("t", id).query().listOfRows()));
        return out;
    }

    @Transactional
    public Map<String, Object> setStatus(UUID actor, UUID id, String status, String reason) {
        if (!java.util.Set.of("ACTIVE", "SUSPENDED", "ARCHIVED").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Status must be ACTIVE, SUSPENDED or ARCHIVED");
        if (jdbc.sql("UPDATE core.tenants SET status = :s, updated_at = now() WHERE id = :i").param("s", status).param("i", id).update() == 0) throw BusinessException.notFound("TENANT_NOT_FOUND", "Tenant not found");
        if ("ACTIVE".equals(status)) {
            // Switching a business back on must also give it a live subscription, or it would stay without any plan features.
            var sub = jdbc.sql("SELECT plan_id, status FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1").param("t", id).query().listOfRows().stream().findFirst();
            if (sub.isPresent() && java.util.Set.of("EXPIRED", "CANCELLED", "PAST_DUE").contains(sub.get().get("status")))
                billing.activatePlan(id, (UUID) sub.get().get("plan_id"), 30);
        }
        directory.invalidateAll();
        audit.record(actor, id, "TENANT_STATUS_CHANGED", "tenant", id, "{\"status\":\"" + status + "\",\"reason\":" + jsonString(reason) + "}");
        return tenant(id);
    }

    /** Changes the plan and/or adds days (30 by default); the business becomes ACTIVE. */
    @Transactional
    public Map<String, Object> changePlan(UUID actor, UUID id, String planCode, Integer days) {
        var tenantType = jdbc.sql("SELECT tenant_type FROM core.tenants WHERE id = :i").param("i", id).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
        var plan = jdbc.sql("SELECT id, tenant_type FROM billing.subscription_plans WHERE code = :c AND is_active").param("c", planCode).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan not found"));
        if (!tenantType.equals(plan.get("tenant_type"))) throw BusinessException.badRequest("PLAN_NOT_FOR_TENANT_TYPE", "This plan is not available for this account type");
        int d = days == null ? 30 : days;
        if (d < 1 || d > 3650) throw BusinessException.badRequest("VALIDATION_ERROR", "Days must be between 1 and 3650");
        billing.activatePlan(id, (UUID) plan.get("id"), d);
        audit.record(actor, id, "TENANT_PLAN_CHANGED", "tenant", id, "{\"plan\":\"" + planCode + "\",\"days\":" + d + "}");
        return tenant(id);
    }

    public Map<String, Object> audit(UUID tenantId, String action, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid)) AND (CAST(:a AS varchar) IS NULL OR action = CAST(:a AS varchar))").param("t", tenantId).param("a", action).query(Long.class).single();
        var rows = jdbc.sql("SELECT id, occurred_at, actor_user_id, tenant_id, action, entity_type, entity_id, metadata_json::text AS metadata FROM audit.audit_logs WHERE (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid)) AND (CAST(:a AS varchar) IS NULL OR action = CAST(:a AS varchar)) ORDER BY occurred_at DESC LIMIT :lim OFFSET :off")
                .param("t", tenantId).param("a", action).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(rows.stream().map(r -> {
            Map<String, Object> m = Rows.camel(r);
            m.put("metadata", r.get("metadata") == null ? null : Rows.json(r.get("metadata")));
            return m;
        }).toList(), total);
    }

    private static String jsonString(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
