package com.platform.commerce;

import com.platform.core.onboarding.TenantProvisioner;
import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import com.platform.core.onboarding.ProvisionOptions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Gives a brand-new store everything needed to sell immediately: profile, main branch, payment + shipping defaults. */
@Component
public class StoreProvisioner implements TenantProvisioner {
    private final JdbcClient jdbc;

    public StoreProvisioner(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public TenantType supports() { return TenantType.STORE; }

    @Override
    public void provision(Tenant t, UUID ownerUserId) {
        doProvision(t, ownerUserId, null);
    }

    @Override
    public List<Map<String, Object>> categories() {
        return StoreSettingsService.listCategories(jdbc);
    }

    /** Sign-up needs the shop's product categories (or "other" with a typed name). */
    @Override
    public void provision(Tenant t, UUID ownerUserId, ProvisionOptions options) {
        doProvision(t, ownerUserId, options.validated("what your shop sells"));
    }

    private void doProvision(Tenant t, UUID ownerUserId, ProvisionOptions o) {
        jdbc.sql("INSERT INTO commerce.store_profiles (tenant_id, store_name) VALUES (:t, :n)").param("t", t.getId()).param("n", t.getName()).update();
        if (o != null) StoreSettingsService.setCategories(jdbc, t.getId(), o.categories(), o.otherCategory());
        jdbc.sql("INSERT INTO commerce.branches (tenant_id, name, code) VALUES (:t, 'Main', 'MAIN')").param("t", t.getId()).update();
        jdbc.sql("INSERT INTO commerce.payment_method_settings (tenant_id, method, enabled) VALUES (:t,'CASH_ON_DELIVERY',TRUE),(:t,'CARD',FALSE)")
                .param("t", t.getId()).update();
        jdbc.sql("""
                INSERT INTO commerce.shipping_methods (tenant_id, type, name, fee_minor, sort_order)
                VALUES (:t,'PICKUP','Store pickup',0,1),(:t,'FIXED','Standard delivery',5000,2)
                """).param("t", t.getId()).update();
    }
}
