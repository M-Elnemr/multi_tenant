package com.platform.commerce;

import com.platform.core.onboarding.ProvisionOptions;
import com.platform.core.onboarding.TenantProvisioner;
import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A new service business gets what a shop gets (profile, main branch, payment + shipping defaults, so it can also sell products) plus working hours it can edit; services are its main business. */
@Component
public class ServiceProvisioner implements TenantProvisioner {
    private final StoreProvisioner store;
    private final JdbcClient jdbc;

    public ServiceProvisioner(StoreProvisioner store, JdbcClient jdbc) { this.store = store; this.jdbc = jdbc; }

    @Override public TenantType supports() { return TenantType.SERVICE; }

    @Override public void provision(Tenant t, UUID ownerUserId) { store.provision(t, ownerUserId); finish(t); }

    @Override public List<Map<String, Object>> categories() { return store.categories(); }

    @Override public void provision(Tenant t, UUID ownerUserId, ProvisionOptions options) { store.provision(t, ownerUserId, options); finish(t); }

    /** The assistant takes appointments from the first day. */
    private void finish(Tenant t) {
        jdbc.sql("INSERT INTO chat.settings (tenant_id, goals) VALUES (:t, '{BOOKINGS}') ON CONFLICT (tenant_id) DO UPDATE SET goals = '{BOOKINGS}'").param("t", t.getId()).update();
    }
}
