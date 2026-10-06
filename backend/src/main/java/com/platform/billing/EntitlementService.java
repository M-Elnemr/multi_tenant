package com.platform.billing;

import com.platform.shared.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The single place plan limits/features are decided (spec 49). Callers ask about a feature or a
 * limit, never about a plan name. A tenant with no live subscription has no entitlements.
 */
@Service
public class EntitlementService {
    private final JdbcClient jdbc;

    public EntitlementService(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Feature(boolean enabled, Long limit) {}

    private Optional<Feature> feature(UUID tenantId, String key) {
        return jdbc.sql("""
                SELECT f.enabled, f.limit_value FROM billing.tenant_subscriptions s
                JOIN billing.subscription_plan_features f ON f.plan_id = s.plan_id
                WHERE s.tenant_id = :t AND s.status IN ('TRIALING','ACTIVE','PAST_DUE') AND f.feature_key = :k
                """).param("t", tenantId).param("k", key)
                .query((rs, n) -> new Feature(rs.getBoolean(1), rs.getObject(2) == null ? null : rs.getLong(2)))
                .optional();
    }

    public boolean hasFeature(UUID tenantId, String key) {
        return feature(tenantId, key).map(Feature::enabled).orElse(false);
    }

    public void requireFeature(UUID tenantId, String key) {
        if (!hasFeature(tenantId, key))
            throw new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_AVAILABLE", "Your plan does not include this feature");
    }

    /** Empty = unlimited. */
    public Optional<Long> limit(UUID tenantId, String key) {
        return feature(tenantId, key).filter(Feature::enabled).map(f -> f.limit() == null ? Long.MAX_VALUE : f.limit())
                .filter(l -> l != Long.MAX_VALUE);
    }

    /** Throws PLAN_LIMIT_REACHED if creating one more item would exceed the limit. Existing data is never touched. */
    public void requireCapacity(UUID tenantId, String key, long currentUsage) {
        Optional<Feature> f = feature(tenantId, key);
        if (f.isEmpty() || !f.get().enabled())
            throw new BusinessException(HttpStatus.FORBIDDEN, "FEATURE_NOT_AVAILABLE", "Your plan does not include this feature");
        if (f.get().limit() != null && currentUsage >= f.get().limit())
            throw new BusinessException(HttpStatus.FORBIDDEN, "PLAN_LIMIT_REACHED", "Plan limit reached for " + key);
    }

    // ---- usage counters (metered, e.g. monthly orders) ------------------------------------------

    public long usage(UUID tenantId, String metric, String period) {
        return jdbc.sql("SELECT value FROM billing.usage_counters WHERE tenant_id=:t AND metric=:m AND period=:p")
                .param("t", tenantId).param("m", metric).param("p", period).query(Long.class).optional().orElse(0L);
    }

    public void increment(UUID tenantId, String metric, String period, long delta) {
        jdbc.sql("""
                INSERT INTO billing.usage_counters (tenant_id, metric, period, value) VALUES (:t,:m,:p,:d)
                ON CONFLICT (tenant_id, metric, period) DO UPDATE SET value = billing.usage_counters.value + :d, updated_at = now()
                """).param("t", tenantId).param("m", metric).param("p", period).param("d", delta).update();
    }
}
