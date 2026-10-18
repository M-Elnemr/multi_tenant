package com.platform.billing;

import com.platform.audit.AuditService;
import com.platform.notifications.NotificationService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.PhoneNormalizer;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online payment is not live yet: an owner presses "Proceed to pay", we store the request with a phone number and
 * customer service contacts them. The platform team marks it contacted / paid (which activates the plan) / rejected.
 */
@Service
public class PayRequestService {
    private final JdbcClient jdbc;
    private final BillingService billing;
    private final NotificationService notifications;
    private final AuditService audit;

    public PayRequestService(JdbcClient jdbc, BillingService billing, NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** One open request per business: pressing the button again returns the request already waiting. */
    @Transactional
    public Map<String, Object> create(UUID tenantId, String tenantType, UUID userId, String planCode, String phoneRaw, String note, String timingRaw) {
        var plan = jdbc.sql("SELECT code, name, tenant_type, price_minor, currency FROM billing.subscription_plans WHERE code = :c AND is_active")
                .param("c", planCode).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan not found"));
        if (!tenantType.equals(plan.get("tenant_type")) || ((Number) plan.get("price_minor")).longValue() <= 0)
            throw BusinessException.badRequest("PLAN_NOT_FOR_TENANT_TYPE", "This plan is not available for your account type");

        String phone = PhoneNormalizer.normalize(phoneRaw);
        if (phone == null) phone = jdbc.sql("SELECT phone FROM core.users WHERE id = :u").param("u", userId).query(String.class).optional().orElse(null);
        if (phone == null || phone.isBlank())
            throw BusinessException.badRequest("PHONE_REQUIRED", "A mobile number is required so customer service can contact you");
        jdbc.sql("UPDATE core.users SET phone = :p WHERE id = :u AND (phone IS NULL OR phone = '')").param("p", phone).param("u", userId).update();

        // Already on a running paid plan and moving to another: "NOW" credits the unused days, "NEXT_PERIOD" changes nothing until renewal.
        var quote = billing.upgradeQuote(tenantId, planCode);
        boolean upgrade = Boolean.TRUE.equals(quote.get("upgrade"));
        String timing = upgrade && "NEXT_PERIOD".equals(timingRaw) ? "NEXT_PERIOD" : "NOW";
        long credit = upgrade && "NOW".equals(timing) ? ((Number) quote.get("creditMinor")).longValue() : 0;
        long amount = upgrade && "NOW".equals(timing) ? ((Number) quote.get("dueNowMinor")).longValue() : ((Number) plan.get("price_minor")).longValue();
        var open = jdbc.sql("SELECT * FROM billing.pay_requests WHERE tenant_id = :t AND status IN ('NEW','CONTACTED')").param("t", tenantId).query().listOfRows().stream().findFirst();
        if (open.isPresent()) {
            // The owner may have picked another plan or number meanwhile: keep the single open request current.
            jdbc.sql("UPDATE billing.pay_requests SET plan_code = :c, amount_minor = :a, timing = :tm, credit_minor = :cr, phone = :p, note = coalesce(:n, note) WHERE id = :i")
                    .param("c", planCode).param("a", amount).param("tm", timing).param("cr", credit).param("p", phone).param("n", note).param("i", open.get().get("id")).update();
            return get((UUID) open.get().get("id"));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO billing.pay_requests (id, tenant_id, requested_by, plan_code, amount_minor, currency, phone, note, timing, credit_minor) VALUES (:i, :t, :u, :c, :a, :cur, :p, :n, :tm, :cr)")
                .param("i", id).param("t", tenantId).param("u", userId).param("c", planCode).param("a", amount).param("tm", timing).param("cr", credit)
                .param("cur", plan.get("currency")).param("p", phone).param("n", note).update();
        notifyPlatform(tenantId, planCode + ("NEXT_PERIOD".equals(timing) ? " (next period)" : upgrade ? " (upgrade now)" : ""));
        audit.record(userId, tenantId, "PAY_REQUEST_CREATED", "pay_request", id, "{\"plan\":\"" + planCode + "\"}");
        return get(id);
    }

    public List<Map<String, Object>> forTenant(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, plan_code, amount_minor, timing, credit_minor, currency, phone, status, created_at, handled_at FROM billing.pay_requests WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 20")
                .param("t", tenantId).query().listOfRows());
    }

    public Map<String, Object> get(UUID id) {
        return Rows.camel(jdbc.sql("SELECT id, tenant_id, plan_code, amount_minor, timing, credit_minor, currency, phone, note, status, admin_note, created_at, handled_at FROM billing.pay_requests WHERE id = :i")
                .param("i", id).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Request not found")));
    }

    // ---- platform side ----------------------------------------------------------------------------

    public Map<String, Object> adminList(String status, Page page) {
        String where = " WHERE (CAST(:s AS varchar) IS NULL OR r.status = CAST(:s AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM billing.pay_requests r" + where).param("s", status).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT r.id, r.tenant_id, r.plan_code, r.amount_minor, r.timing, r.credit_minor, r.currency, r.phone, r.note, r.status, r.admin_note, r.created_at, r.handled_at,
                       t.name AS tenant_name, t.slug, t.tenant_type, t.status AS tenant_status,
                       (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary) AS primary_host,
                       u.first_name || ' ' || coalesce(u.last_name, '') AS requester_name, u.email AS requester_email
                FROM billing.pay_requests r JOIN core.tenants t ON t.id = r.tenant_id LEFT JOIN core.users u ON u.id = r.requested_by
                """ + where + " ORDER BY (r.status IN ('NEW','CONTACTED')) DESC, r.created_at DESC LIMIT :lim OFFSET :off")
                .param("s", status).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    public long newCount() {
        return jdbc.sql("SELECT count(*) FROM billing.pay_requests WHERE status = 'NEW'").query(Long.class).single();
    }

    @Transactional
    public Map<String, Object> handle(UUID actor, UUID id, String status, String adminNote, boolean activate) {
        if (!Set.of("CONTACTED", "PAID", "REJECTED", "SCHEDULED").contains(status))
            throw BusinessException.badRequest("VALIDATION_ERROR", "Status must be CONTACTED, PAID, SCHEDULED or REJECTED");
        var r = jdbc.sql("SELECT tenant_id, plan_code, status, timing, credit_minor FROM billing.pay_requests WHERE id = :i FOR UPDATE").param("i", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Request not found"));
        UUID tenantId = (UUID) r.get("tenant_id");
        boolean next = "NEXT_PERIOD".equals(r.get("timing"));
        if ("PAID".equals(status) && next) throw BusinessException.badRequest("VALIDATION_ERROR", "This upgrade starts next period and nothing is paid now: mark it SCHEDULED");
        if ("SCHEDULED".equals(status) && !next) throw BusinessException.badRequest("VALIDATION_ERROR", "Only a next-period upgrade can be scheduled");
        UUID planId = billing.planId((String) r.get("plan_code"));
        if ("SCHEDULED".equals(status)) billing.schedulePlan(tenantId, planId);
        else if ("PAID".equals(status) && activate) {
            if (((Number) r.get("credit_minor")).longValue() > 0) billing.restartPlan(tenantId, planId, billing.planDays(planId));   // upgrade now: a fresh period on the new plan
            else billing.activatePlan(tenantId, planId, billing.planDays(planId));
        }
        jdbc.sql("UPDATE billing.pay_requests SET status = :s, admin_note = coalesce(:n, admin_note), handled_by = :a, handled_at = now() WHERE id = :i")
                .param("s", status).param("n", adminNote).param("a", actor).param("i", id).update();
        audit.record(actor, tenantId, "PAY_REQUEST_" + status, "pay_request", id, null);
        return get(id);
    }

    /** Tells every platform operator (in-app + phone push); the notification belongs to no tenant. */
    private void notifyPlatform(UUID tenantId, String planCode) {
        String name = jdbc.sql("SELECT name FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).optional().orElse("");
        String title = "طلب دفع جديد";
        String body = name + " - " + planCode;
        String payload = "{\"type\":\"PAY_REQUEST\",\"tenantId\":\"" + tenantId + "\"}";
        List<UUID> admins = jdbc.sql("""
                SELECT DISTINCT pr.user_id FROM core.user_platform_roles pr JOIN core.role_permissions rp ON rp.role_id = pr.role_id
                JOIN core.permissions p ON p.id = rp.permission_id JOIN core.users u ON u.id = pr.user_id
                WHERE p.code = 'platform.tenant.manage' AND u.status = 'ACTIVE'
                """).query(UUID.class).list();
        for (UUID u : admins) {
            notifications.notify(u, null, "PAY_REQUEST", title, body, payload, false);
            notifications.push(u, null, title, body, payload);
        }
        if (!admins.isEmpty()) notifications.dispatchSoon();
    }

    public Map<String, Object> ownerContact(UUID tenantId) {
        var rows = jdbc.sql("""
                SELECT u.id, u.first_name, u.last_name, u.phone, u.email FROM core.user_tenant_memberships m
                JOIN core.membership_roles mr ON mr.membership_id = m.id JOIN core.roles r ON r.id = mr.role_id JOIN core.users u ON u.id = m.user_id
                WHERE m.tenant_id = :t AND r.code IN ('STORE_OWNER','CLINIC_OWNER') ORDER BY m.created_at LIMIT 1
                """).param("t", tenantId).query().listOfRows();
        return rows.isEmpty() ? new LinkedHashMap<>() : Rows.camel(rows.get(0));
    }
}
