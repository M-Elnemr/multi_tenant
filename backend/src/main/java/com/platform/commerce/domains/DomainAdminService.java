package com.platform.commerce.domains;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform-admin side: cross-tenant order list with margin, the price-rule editor, provider error log, retry. */
@Service
public class DomainAdminService {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ProviderErrorLog errors;
    private final DomainPricingService pricing;

    public DomainAdminService(JdbcClient jdbc, AuditService audit, ProviderErrorLog errors, DomainPricingService pricing) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.errors = errors;
        this.pricing = pricing;
    }

    public Map<String, Object> orders(String status, UUID tenantId, String q, Page page) {
        String st = blank(status) ? null : status.toUpperCase();
        String like = blank(q) ? null : "%" + q.trim() + "%";
        long total = jdbc.sql("""
                SELECT count(*) FROM commerce.domain_orders o JOIN core.tenants t ON t.id = o.tenant_id
                WHERE (CAST(:s AS varchar) IS NULL OR o.status = CAST(:s AS varchar)) AND (CAST(:tid AS uuid) IS NULL OR o.tenant_id = CAST(:tid AS uuid))
                  AND (CAST(:q AS varchar) IS NULL OR o.domain ILIKE CAST(:q AS varchar) OR t.name ILIKE CAST(:q AS varchar))
                """).param("s", st).param("tid", tenantId).param("q", like).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT o.id, o.tenant_id, t.name AS tenant_name, o.domain, o.kind, o.status, o.price_minor, o.provider_price_minor, o.currency, o.years,
                       o.provider_order_id, o.last_error, o.last_error_code, o.auto_renew, o.expires_at, o.created_at
                FROM commerce.domain_orders o JOIN core.tenants t ON t.id = o.tenant_id
                WHERE (CAST(:s AS varchar) IS NULL OR o.status = CAST(:s AS varchar)) AND (CAST(:tid AS uuid) IS NULL OR o.tenant_id = CAST(:tid AS uuid))
                  AND (CAST(:q AS varchar) IS NULL OR o.domain ILIKE CAST(:q AS varchar) OR t.name ILIKE CAST(:q AS varchar))
                ORDER BY o.created_at DESC LIMIT :l OFFSET :off
                """).param("s", st).param("tid", tenantId).param("q", like).param("l", page.pageSize()).param("off", page.offset()).query().listOfRows();
        List<Map<String, Object>> data = rows.stream().map(r -> {
            var m = Rows.camel(r);
            Object merchant = m.get("priceMinor"), cost = m.get("providerPriceMinor");
            if (merchant instanceof Number mp && cost instanceof Number cp) m.put("marginMinor", mp.longValue() - cp.longValue());
            m.put("retryable", m.get("lastErrorCode") != null && ProviderException.isRetryableCode((String) m.get("lastErrorCode")));
            return m;
        }).toList();
        return page.wrap(data, total);
    }

    public List<Map<String, Object>> priceRules() {
        return Rows.camel(jdbc.sql("SELECT id, tld, markup_kind, markup_value, currency, promo_price_minor, promo_expires_at, active, created_at, updated_at FROM commerce.domain_price_rules ORDER BY tld").query().listOfRows());
    }

    public Map<String, Object> savePriceRule(DomainPricingService.RuleReq r) { return pricing.upsert(r); }

    /** Only when the recorded failure code is itself retryable; a genuine non-retryable failure was already refunded and is not meant to be retried. */
    @Transactional
    public Map<String, Object> retry(UUID actor, UUID orderId) {
        var o = jdbc.sql("SELECT id, tenant_id, domain, status, last_error_code FROM commerce.domain_orders WHERE id = :i FOR UPDATE")
                .param("i", orderId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Order not found"));
        if (!"FAILED".equals(o.get("status"))) throw BusinessException.conflict("NOT_RETRYABLE", "Only a failed order can be retried");
        String code = (String) o.get("last_error_code");
        if (!ProviderException.isRetryableCode(code)) throw BusinessException.conflict("NOT_RETRYABLE", "This failure is not retryable");
        jdbc.sql("UPDATE commerce.domain_orders SET status = 'PAID', attempts = 0, next_attempt_at = now(), last_error = NULL, last_error_code = NULL WHERE id = :i").param("i", orderId).update();
        jdbc.sql("INSERT INTO commerce.domain_order_events (order_id, status, note) VALUES (:i, 'PAID', 'Retried by a platform admin')").param("i", orderId).update();
        audit.record(actor, (UUID) o.get("tenant_id"), "DOMAIN_ORDER_RETRIED", "domain_order", orderId, null);
        return Map.of("id", orderId, "status", "PAID");
    }

    public List<Map<String, Object>> providerErrors(int limit) { return errors.recent(limit); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
