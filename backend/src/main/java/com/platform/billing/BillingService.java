package com.platform.billing;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.PlatformProperties;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.platform.core.tenant.TenantCreatedEvent;

@Service
public class BillingService {
    private final JdbcClient jdbc;
    private final PlatformProperties props;
    private final List<PaymentProvider> providers;
    private final AuditService audit;
    private final int graceDays;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.platform.core.tenant.TenantDirectory directory;

    public BillingService(JdbcClient jdbc, PlatformProperties props, List<PaymentProvider> providers, AuditService audit,
                          @Value("${app.billing.grace-days:14}") int graceDays, org.springframework.context.ApplicationEventPublisher events, com.platform.core.tenant.TenantDirectory directory) {
        this.events = events;
        this.directory = directory;
        this.jdbc = jdbc;
        this.props = props;
        this.providers = providers;
        this.audit = audit;
        this.graceDays = graceDays;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Every new tenant starts a free trial (all Professional features) of its type. Runs in the onboarding transaction. */
    @EventListener
    public void onTenantCreated(TenantCreatedEvent e) {
        String planCode = e.type().name() + "_FREE";
        Instant end = Instant.now().plus(props.trialDays(), ChronoUnit.DAYS);
        jdbc.sql("""
                INSERT INTO billing.tenant_subscriptions (tenant_id, plan_id, status, current_period_end)
                SELECT :t, id, 'TRIALING', :e FROM billing.subscription_plans WHERE code = :c
                """).param("t", e.tenantId()).param("c", planCode).param("e", java.sql.Timestamp.from(end)).update();
        event(e.tenantId(), "TRIAL_STARTED", "{\"plan\":\"" + planCode + "\"}");
    }

    public List<Map<String, Object>> plans(String tenantType) {
        return jdbc.sql("""
                SELECT p.code, p.name, p.tenant_type, p.price_minor, p.currency, p.interval,
                       (SELECT coalesce(jsonb_object_agg(f.feature_key, CASE WHEN NOT f.enabled THEN 'false'::jsonb
                            WHEN f.limit_value IS NULL THEN 'true'::jsonb ELSE to_jsonb(f.limit_value) END), '{}'::jsonb)::text
                        FROM billing.subscription_plan_features f WHERE f.plan_id = p.id) AS features
                FROM billing.subscription_plans p
                WHERE p.is_active AND (CAST(:type AS varchar) IS NULL OR p.tenant_type = CAST(:type AS varchar)) ORDER BY p.tenant_type, p.sort_order
                """).param("type", tenantType).query().listOfRows().stream().map(r -> {
                    var m = com.platform.shared.Rows.camel(r);
                    m.put("features", com.platform.shared.Rows.json(r.get("features")));
                    return m;
                }).toList();
    }

    public Map<String, Object> subscription(UUID tenantId) {
        return jdbc.sql("""
                SELECT s.id, s.status, s.current_period_start, s.current_period_end, s.cancel_at_period_end,
                       p.code AS plan_code, p.name AS plan_name, p.price_minor, p.currency
                FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t AND s.status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED')
                """).param("t", tenantId).query().listOfRows().stream().findFirst()
                .map(com.platform.shared.Rows::camel)
                .orElseThrow(() -> new BusinessException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_REQUIRED", "No active subscription"));
    }

    public List<Map<String, Object>> invoices(UUID tenantId) {
        return jdbc.sql("SELECT id, invoice_number, status, currency, total_minor, issued_at, paid_at FROM billing.subscription_invoices WHERE tenant_id = :t ORDER BY issued_at DESC LIMIT 100")
                .param("t", tenantId).query().listOfRows().stream().map(com.platform.shared.Rows::camel).toList();
    }

    /** Creates an OPEN invoice + pending payment for moving to the given plan. Returns checkout details. */
    @Transactional
    public Map<String, Object> changePlan(UUID tenantId, UUID actor, String tenantType, String planCode, String providerCode) {
        var plan = jdbc.sql("SELECT id, code, name, tenant_type, price_minor, currency FROM billing.subscription_plans WHERE code = :c AND is_active")
                .param("c", planCode).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan not found"));
        if (!tenantType.equals(plan.get("tenant_type"))) throw BusinessException.badRequest("PLAN_NOT_FOR_TENANT_TYPE", "This plan is not available for your account type");
        Map<String, Object> current = subscription(tenantId);
        if (planCode.equals(current.get("planCode")) && "ACTIVE".equals(current.get("status")))
            throw BusinessException.badRequest("ALREADY_ON_PLAN", "You are already on this plan");

        UUID planId = (UUID) plan.get("id");
        long price = ((Number) plan.get("price_minor")).longValue();
        String currency = (String) plan.get("currency");
        UUID invoiceId = UUID.randomUUID();
        String number = "INV-" + Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toString().replace("-", "") + "-" + invoiceId.toString().substring(0, 6).toUpperCase();
        jdbc.sql("""
                INSERT INTO billing.subscription_invoices (id, tenant_id, subscription_id, invoice_number, status, currency, total_minor, target_plan_id)
                VALUES (:id, :t, :s, :n, 'OPEN', :c, :total, :p)
                """).param("id", invoiceId).param("t", tenantId).param("s", current.get("id")).param("n", number)
                .param("c", currency).param("total", price).param("p", planId).update();
        jdbc.sql("INSERT INTO billing.subscription_invoice_items (invoice_id, description, amount_minor) VALUES (:i, :d, :a)")
                .param("i", invoiceId).param("d", plan.get("name") + " - 1 month").param("a", price).update();

        if (price == 0) {
            applyPaid(invoiceId);
            return Map.of("invoiceId", invoiceId, "status", "PAID");
        }
        PaymentProvider provider = providers.stream().filter(p -> p.code().equals(providerCode)).findFirst()
                .orElseThrow(() -> BusinessException.badRequest("PAYMENT_PROVIDER_UNKNOWN", "Unknown payment provider"));
        String key = "sub-" + invoiceId;
        var intent = provider.createPaymentIntent(tenantId, invoiceId, price, currency, key);
        jdbc.sql("""
                INSERT INTO billing.subscription_payments (tenant_id, invoice_id, provider, provider_payment_id, amount_minor, currency, status, idempotency_key)
                VALUES (:t, :i, :pv, :pid, :a, :c, 'PENDING', :k)
                """).param("t", tenantId).param("i", invoiceId).param("pv", provider.code()).param("pid", intent.providerPaymentId())
                .param("a", price).param("c", currency).param("k", key).update();
        audit.record(actor, tenantId, "SUBSCRIPTION_CHANGE_REQUESTED", "invoice", invoiceId, "{\"plan\":\"" + planCode + "\"}");
        return Map.of("invoiceId", invoiceId, "invoiceNumber", number, "status", "OPEN",
                "providerPaymentId", intent.providerPaymentId(), "checkoutUrl", intent.checkoutUrl());
    }

    @Transactional
    public void cancel(UUID tenantId, UUID actor) {
        jdbc.sql("UPDATE billing.tenant_subscriptions SET cancel_at_period_end = TRUE, updated_at = now() WHERE tenant_id = :t AND status IN ('TRIALING','ACTIVE','PAST_DUE')")
                .param("t", tenantId).update();
        event(tenantId, "CANCEL_SCHEDULED", null);
        audit.record(actor, tenantId, "SUBSCRIPTION_CANCEL_SCHEDULED", "tenant", tenantId, null);
    }

    // ---- webhooks (idempotent) -----------------------------------------------------------------

    public enum WebhookResult { PROCESSED, DUPLICATE, IGNORED }

    @Transactional
    public WebhookResult handleWebhook(String providerCode, String rawBody, String signature) {
        PaymentProvider provider = providers.stream().filter(p -> p.code().equals(providerCode)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Unknown provider"));
        if (!provider.verifySignature(rawBody, signature))
            throw BusinessException.unauthorized("INVALID_SIGNATURE", "Invalid webhook signature");
        Map<String, Object> body = JsonParserFactory.getJsonParser().parseMap(rawBody);
        String eventId = String.valueOf(body.get("eventId"));
        if (body.get("eventId") == null) throw BusinessException.badRequest("INVALID_WEBHOOK", "eventId missing");
        // UNIQUE(provider, event_id): a repeated delivery inserts nothing and is acknowledged without side effects.
        int inserted = jdbc.sql("""
                INSERT INTO billing.webhook_events (provider, event_id, signature_verified, payload_json, status)
                VALUES (:p, :e, TRUE, CAST(:b AS jsonb), 'RECEIVED') ON CONFLICT (provider, event_id) DO NOTHING
                """).param("p", providerCode).param("e", eventId).param("b", rawBody).update();
        if (inserted == 0) return WebhookResult.DUPLICATE;

        String type = String.valueOf(body.get("type"));
        UUID invoiceId = UUID.fromString(String.valueOf(body.get("invoiceId")));
        WebhookResult result = WebhookResult.IGNORED;
        if ("payment.succeeded".equals(type)) {
            jdbc.sql("UPDATE billing.subscription_payments SET status = 'SUCCEEDED', updated_at = now() WHERE invoice_id = :i AND provider = :p")
                    .param("i", invoiceId).param("p", providerCode).update();
            applyPaid(invoiceId);
            result = WebhookResult.PROCESSED;
        } else if ("payment.failed".equals(type)) {
            jdbc.sql("UPDATE billing.subscription_payments SET status = 'FAILED', updated_at = now() WHERE invoice_id = :i AND provider = :p AND status = 'PENDING'")
                    .param("i", invoiceId).param("p", providerCode).update();
            result = WebhookResult.PROCESSED;
        }
        jdbc.sql("UPDATE billing.webhook_events SET status = :s, processed_at = now() WHERE provider = :p AND event_id = :e")
                .param("s", result.name()).param("p", providerCode).param("e", eventId).update();
        return result;
    }

    /** Marks the invoice paid and moves the tenant onto the purchased plan. Safe to call twice. */
    private void applyPaid(UUID invoiceId) {
        var inv = jdbc.sql("SELECT tenant_id, target_plan_id, status FROM billing.subscription_invoices WHERE id = :i FOR UPDATE")
                .param("i", invoiceId).query().listOfRows().stream().findFirst().orElse(null);
        if (inv == null || !"OPEN".equals(inv.get("status"))) return;
        UUID tenantId = (UUID) inv.get("tenant_id");
        jdbc.sql("UPDATE billing.subscription_invoices SET status = 'PAID', paid_at = now() WHERE id = :i").param("i", invoiceId).update();
        activatePlan(tenantId, (UUID) inv.get("target_plan_id"), planDays((UUID) inv.get("target_plan_id")));
        event(tenantId, "PAYMENT_APPLIED", "{\"invoiceId\":\"" + invoiceId + "\"}");
    }

    // ---- plan activation (payment webhook and platform admin) ------------------------------------

    /**
     * Puts the tenant on the plan as ACTIVE for another {@code days} days. A still-running paid period is extended, a
     * trial / lapsed / expired one starts today. Also re-enables a suspended tenant.
     */
    @Transactional
    public void activatePlan(UUID tenantId, UUID planId, int days) {
        int n = jdbc.sql("""
                UPDATE billing.tenant_subscriptions SET plan_id = :p, status = 'ACTIVE',
                       current_period_start = CASE WHEN status = 'ACTIVE' AND current_period_end > now() THEN current_period_start ELSE now() END,
                       current_period_end = (CASE WHEN status = 'ACTIVE' AND current_period_end > now() THEN current_period_end ELSE now() END) + make_interval(days => :d),
                       cancel_at_period_end = FALSE, pending_plan_id = NULL, updated_at = now()
                WHERE id = (SELECT id FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1)
                """).param("p", planId).param("t", tenantId).param("d", days).update();
        if (n == 0) {
            jdbc.sql("INSERT INTO billing.tenant_subscriptions (tenant_id, plan_id, status, current_period_end) VALUES (:t, :p, 'ACTIVE', now() + make_interval(days => :d))")
                    .param("t", tenantId).param("p", planId).param("d", days).update();
        }
        jdbc.sql("UPDATE core.tenants SET status = 'ACTIVE', updated_at = now() WHERE id = :t AND status IN ('TRIAL','PAST_DUE','SUSPENDED','ACTIVE')")
                .param("t", tenantId).update();
        directory.invalidateAll();
        event(tenantId, "PLAN_ACTIVATED", "{\"days\":" + days + "}");
    }

    private Map<String, Object> runningPaid(UUID tenantId) {
        return jdbc.sql("""
                SELECT s.id, s.plan_id, p.code AS plan_code, p.price_minor, s.current_period_start, s.current_period_end
                FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t AND s.status = 'ACTIVE' AND s.current_period_end > now() AND p.price_minor > 0
                ORDER BY s.created_at DESC LIMIT 1
                """).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
    }

    /**
     * What moving to another plan costs. For a business in a running paid period the answer carries the two options:
     * upgrade now (the unused days of the current plan are credited against the new price) or from the next period.
     */
    public Map<String, Object> upgradeQuote(UUID tenantId, String planCode) {
        var plan = jdbc.sql("SELECT code, price_minor, currency FROM billing.subscription_plans WHERE code = :c AND is_active").param("c", planCode).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan not found"));
        long full = ((Number) plan.get("price_minor")).longValue();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("planCode", planCode);
        out.put("fullMinor", full);
        out.put("currency", plan.get("currency"));
        var cur = runningPaid(tenantId);
        if (cur == null || planCode.equals(cur.get("plan_code"))) { out.put("upgrade", false); out.put("creditMinor", 0L); out.put("dueNowMinor", full); return out; }
        Instant start = ((java.sql.Timestamp) cur.get("current_period_start")).toInstant(), end = ((java.sql.Timestamp) cur.get("current_period_end")).toInstant();
        long totalDays = Math.max(1, Math.round((end.toEpochMilli() - start.toEpochMilli()) / 86_400_000.0));
        long remaining = Math.min(totalDays, Math.max(0, (long) Math.ceil((end.toEpochMilli() - System.currentTimeMillis()) / 86_400_000.0)));
        long credit = ((Number) cur.get("price_minor")).longValue() * remaining / totalDays;
        out.put("upgrade", true);
        out.put("currentPlanCode", cur.get("plan_code"));
        out.put("periodEnd", end.toString());
        out.put("remainingDays", remaining);
        out.put("totalDays", totalDays);
        out.put("creditMinor", credit);
        out.put("dueNowMinor", Math.max(0, full - credit));
        return out;
    }

    /** Upgrade right now, paid: the new plan starts today for a full period (the unused days were credited in the price). */
    @Transactional
    public void restartPlan(UUID tenantId, UUID planId, int days) {
        jdbc.sql("""
                UPDATE billing.tenant_subscriptions SET plan_id = :p, status = 'ACTIVE', current_period_start = now(), current_period_end = now() + make_interval(days => :d),
                       cancel_at_period_end = FALSE, pending_plan_id = NULL, updated_at = now()
                WHERE id = (SELECT id FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1)
                """).param("p", planId).param("t", tenantId).param("d", days).update();
        jdbc.sql("UPDATE core.tenants SET status = 'ACTIVE', updated_at = now() WHERE id = :t AND status IN ('TRIAL','PAST_DUE','SUSPENDED','ACTIVE')").param("t", tenantId).update();
        directory.invalidateAll();
        event(tenantId, "PLAN_UPGRADED_NOW", "{\"days\":" + days + "}");
    }

    /** Platform admin sets the plan AND the final number of days left (counted from today): the days are not added to what is already there. */
    @Transactional
    public void setPlanWithDaysLeft(UUID tenantId, UUID planId, int daysLeft) {
        int n = jdbc.sql("""
                UPDATE billing.tenant_subscriptions SET plan_id = :p, status = 'ACTIVE',
                       current_period_start = CASE WHEN status = 'ACTIVE' AND current_period_end > now() THEN current_period_start ELSE now() END,
                       current_period_end = now() + make_interval(days => :d), cancel_at_period_end = FALSE, pending_plan_id = NULL, updated_at = now()
                WHERE id = (SELECT id FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1)
                """).param("p", planId).param("t", tenantId).param("d", daysLeft).update();
        if (n == 0) jdbc.sql("INSERT INTO billing.tenant_subscriptions (tenant_id, plan_id, status, current_period_end) VALUES (:t, :p, 'ACTIVE', now() + make_interval(days => :d))")
                .param("t", tenantId).param("p", planId).param("d", daysLeft).update();
        jdbc.sql("UPDATE core.tenants SET status = 'ACTIVE', updated_at = now() WHERE id = :t AND status IN ('TRIAL','PAST_DUE','SUSPENDED','ACTIVE')").param("t", tenantId).update();
        directory.invalidateAll();
        event(tenantId, "PLAN_SET", "{\"daysLeft\":" + daysLeft + "}");
    }

    /** Platform admin moves a running paid business to another plan: the plan changes, the end date stays (no days are added). False when there is no running paid period to keep. */
    @Transactional
    public boolean switchPlanKeepingPeriod(UUID tenantId, UUID planId) {
        var cur = runningPaid(tenantId);
        if (cur == null) return false;
        jdbc.sql("UPDATE billing.tenant_subscriptions SET plan_id = :p, pending_plan_id = NULL, updated_at = now() WHERE id = :i").param("p", planId).param("i", cur.get("id")).update();
        directory.invalidateAll();
        event(tenantId, "PLAN_SWITCHED", null);
        return true;
    }

    /** The upgrade was agreed for the next period: nothing changes now, the renewal is offered on this plan. */
    @Transactional
    public void schedulePlan(UUID tenantId, UUID planId) {
        jdbc.sql("UPDATE billing.tenant_subscriptions SET pending_plan_id = :p, updated_at = now() WHERE id = (SELECT id FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 1)")
                .param("p", planId).param("t", tenantId).update();
        event(tenantId, "PLAN_UPGRADE_SCHEDULED", null);
    }

    /** How long one purchase of this plan lasts: 30 days for a monthly plan, 365 for a yearly one. */
    public int planDays(UUID planId) {
        return "YEAR".equals(jdbc.sql("SELECT interval FROM billing.subscription_plans WHERE id = :p").param("p", planId).query(String.class).optional().orElse("MONTH")) ? 365 : 30;
    }

    public UUID planId(String code) {
        return jdbc.sql("SELECT id FROM billing.subscription_plans WHERE code = :c AND is_active").param("c", code).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Plan not found"));
    }

    /**
     * What the apps need for the "free plan ends / renew" bar and the disabled screen. Works for a suspended tenant too.
     * state: OK, TRIAL_ENDING (free plan, 5 days or less), WAIT_RENEW (paid period over, grace running), DISABLED.
     */
    public Map<String, Object> status(UUID tenantId) {
        var tenantStatus = jdbc.sql("SELECT status FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single();
        var sub = jdbc.sql("""
                SELECT s.status, s.current_period_end, s.cancel_at_period_end, p.code AS plan_code, p.name AS plan_name, p.price_minor, p.currency, p.tenant_type,
                       (SELECT np.code FROM billing.subscription_plans np WHERE np.id = s.pending_plan_id) AS pending_plan_code
                FROM billing.tenant_subscriptions s JOIN billing.subscription_plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :t ORDER BY s.created_at DESC LIMIT 1
                """).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("tenantStatus", tenantStatus);
        if (sub == null) { out.put("state", "DISABLED"); return out; }
        Instant end = ((java.sql.Timestamp) sub.get("current_period_end")).toInstant();
        String subStatus = (String) sub.get("status");
        long daysLeft = (long) Math.ceil((end.toEpochMilli() - System.currentTimeMillis()) / 86_400_000.0);
        Instant graceEnd = end.plus(graceDays, ChronoUnit.DAYS);
        String state = "OK";
        if (!java.util.Set.of("TRIAL", "ACTIVE", "PAST_DUE").contains(tenantStatus) || java.util.Set.of("EXPIRED", "CANCELLED").contains(subStatus)) state = "DISABLED";
        else if ("PAST_DUE".equals(subStatus)) { state = "WAIT_RENEW"; daysLeft = (long) Math.ceil((graceEnd.toEpochMilli() - System.currentTimeMillis()) / 86_400_000.0); }
        else if ("TRIALING".equals(subStatus) && daysLeft <= 5) state = "TRIAL_ENDING";
        out.put("state", state);
        out.put("subscriptionStatus", subStatus);
        out.put("planCode", sub.get("plan_code"));
        out.put("planName", sub.get("plan_name"));
        out.put("tenantType", sub.get("tenant_type"));
        out.put("priceMinor", sub.get("price_minor"));
        out.put("currency", sub.get("currency"));
        out.put("periodEnd", end.toString());
        out.put("pendingPlanCode", sub.get("pending_plan_code"));
        out.put("graceEnd", graceEnd.toString());
        out.put("daysLeft", Math.max(daysLeft, 0));
        out.put("trial", "TRIALING".equals(subStatus) || String.valueOf(sub.get("plan_code")).endsWith("_FREE"));
        return out;
    }

    // ---- expiry job -------------------------------------------------------------------------------

    /**
     * Free trial over -> EXPIRED + tenant SUSPENDED at once. Paid period over -> PAST_DUE ("waiting for renew", still working)
     * for the grace days -> EXPIRED + SUSPENDED. Data is never deleted by the job.
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    public void expiryCheck() {
        for (var row : jdbc.sql("SELECT tenant_id, status, cancel_at_period_end FROM billing.tenant_subscriptions WHERE status IN ('TRIALING','ACTIVE') AND current_period_end < now()")
                .query().listOfRows()) {
            UUID t = (UUID) row.get("tenant_id");
            boolean trial = "TRIALING".equals(row.get("status"));
            boolean cancelled = Boolean.TRUE.equals(row.get("cancel_at_period_end"));
            if (trial) {
                jdbc.sql("UPDATE billing.tenant_subscriptions SET status = 'EXPIRED', updated_at = now() WHERE tenant_id = :t AND status = 'TRIALING'").param("t", t).update();
                jdbc.sql("UPDATE core.tenants SET status = 'SUSPENDED', updated_at = now() WHERE id = :t").param("t", t).update();
                tenantStateChanged();
                event(t, "TRIAL_ENDED", null);
                events.publishEvent(new SubscriptionEvents.StateChanged(t, "SUSPENDED"));
                continue;
            }
            jdbc.sql("UPDATE billing.tenant_subscriptions SET status = CASE WHEN cancel_at_period_end THEN 'CANCELLED' ELSE 'PAST_DUE' END, updated_at = now() WHERE tenant_id = :t AND status = 'ACTIVE'")
                    .param("t", t).update();
            jdbc.sql("UPDATE core.tenants SET status = :s, updated_at = now() WHERE id = :t").param("s", cancelled ? "CANCELLED" : "PAST_DUE").param("t", t).update();
            tenantStateChanged();
            event(t, "PERIOD_ENDED", null);
            events.publishEvent(new SubscriptionEvents.StateChanged(t, cancelled ? "CANCELLED" : "PAST_DUE"));
        }
        for (var row : jdbc.sql("SELECT tenant_id FROM billing.tenant_subscriptions WHERE status = 'PAST_DUE' AND current_period_end < now() - make_interval(days => :g)")
                .param("g", graceDays).query().listOfRows()) {
            UUID t = (UUID) row.get("tenant_id");
            jdbc.sql("UPDATE billing.tenant_subscriptions SET status = 'EXPIRED', updated_at = now() WHERE tenant_id = :t AND status = 'PAST_DUE'").param("t", t).update();
            jdbc.sql("UPDATE core.tenants SET status = 'SUSPENDED', updated_at = now() WHERE id = :t").param("t", t).update();
            tenantStateChanged();
            event(t, "SUSPENDED_FOR_NONPAYMENT", null);
            events.publishEvent(new SubscriptionEvents.StateChanged(t, "SUSPENDED"));
        }
    }

    private void tenantStateChanged() { directory.invalidateAll(); }

    private void event(UUID tenantId, String type, String details) {
        jdbc.sql("INSERT INTO billing.subscription_events (tenant_id, event_type, details) VALUES (:t, :e, CAST(:d AS jsonb))")
                .param("t", tenantId).param("e", type).param("d", details).update();
    }
}
