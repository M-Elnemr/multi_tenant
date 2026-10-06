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

    public BillingService(JdbcClient jdbc, PlatformProperties props, List<PaymentProvider> providers, AuditService audit,
                          @Value("${app.billing.grace-days:7}") int graceDays, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.jdbc = jdbc;
        this.props = props;
        this.providers = providers;
        this.audit = audit;
        this.graceDays = graceDays;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Every new tenant starts a trial on the full-featured (Pro) plan of its type. Runs in the onboarding transaction. */
    @EventListener
    public void onTenantCreated(TenantCreatedEvent e) {
        String planCode = e.type().name() + "_PRO";
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
        jdbc.sql("""
                UPDATE billing.tenant_subscriptions SET plan_id = :p, status = 'ACTIVE', current_period_start = now(),
                       current_period_end = now() + interval '1 month', cancel_at_period_end = FALSE, updated_at = now()
                WHERE tenant_id = :t AND status IN ('TRIALING','ACTIVE','PAST_DUE','PAUSED')
                """).param("p", inv.get("target_plan_id")).param("t", tenantId).update();
        jdbc.sql("UPDATE core.tenants SET status = 'ACTIVE', updated_at = now() WHERE id = :t AND status IN ('TRIAL','PAST_DUE','SUSPENDED')")
                .param("t", tenantId).update();
        event(tenantId, "PAYMENT_APPLIED", "{\"invoiceId\":\"" + invoiceId + "\"}");
    }

    // ---- expiry job -------------------------------------------------------------------------------

    /** Trial/period end -> PAST_DUE (grace) -> EXPIRED + tenant SUSPENDED. Data is never deleted (spec 7). */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    public void expiryCheck() {
        for (var row : jdbc.sql("SELECT tenant_id FROM billing.tenant_subscriptions WHERE status IN ('TRIALING','ACTIVE') AND current_period_end < now()")
                .query().listOfRows()) {
            UUID t = (UUID) row.get("tenant_id");
            jdbc.sql("""
                    UPDATE billing.tenant_subscriptions SET status = CASE WHEN cancel_at_period_end THEN 'CANCELLED' ELSE 'PAST_DUE' END, updated_at = now()
                    WHERE tenant_id = :t AND status IN ('TRIALING','ACTIVE')
                    """).param("t", t).update();
            jdbc.sql("UPDATE core.tenants SET status = CASE WHEN (SELECT status FROM billing.tenant_subscriptions WHERE tenant_id = :t ORDER BY updated_at DESC LIMIT 1) = 'CANCELLED' THEN 'CANCELLED' ELSE 'PAST_DUE' END, updated_at = now() WHERE id = :t")
                    .param("t", t).update();
            event(t, "PERIOD_ENDED", null);
            events.publishEvent(new SubscriptionEvents.StateChanged(t, "PAST_DUE"));
        }
        for (var row : jdbc.sql("SELECT tenant_id FROM billing.tenant_subscriptions WHERE status = 'PAST_DUE' AND current_period_end < now() - make_interval(days => :g)")
                .param("g", graceDays).query().listOfRows()) {
            UUID t = (UUID) row.get("tenant_id");
            jdbc.sql("UPDATE billing.tenant_subscriptions SET status = 'EXPIRED', updated_at = now() WHERE tenant_id = :t AND status = 'PAST_DUE'").param("t", t).update();
            jdbc.sql("UPDATE core.tenants SET status = 'SUSPENDED', updated_at = now() WHERE id = :t").param("t", t).update();
            event(t, "SUSPENDED_FOR_NONPAYMENT", null);
            events.publishEvent(new SubscriptionEvents.StateChanged(t, "SUSPENDED"));
        }
    }

    private void event(UUID tenantId, String type, String details) {
        jdbc.sql("INSERT INTO billing.subscription_events (tenant_id, event_type, details) VALUES (:t, :e, CAST(:d AS jsonb))")
                .param("t", tenantId).param("e", type).param("d", details).update();
    }
}
