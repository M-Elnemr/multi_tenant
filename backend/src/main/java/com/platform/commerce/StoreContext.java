package com.platform.commerce;

import com.platform.shared.BusinessException;
import com.platform.shared.TenantContext;
import java.util.Set;
import java.util.UUID;

/** Guards for store endpoints: the host must resolve to a STORE tenant that can currently operate. */
public final class StoreContext {
    private static final Set<String> OPEN = Set.of("TRIAL", "ACTIVE", "PAST_DUE");

    private StoreContext() {}

    /** For admin/read endpoints: any lifecycle state except unknown host / wrong type. */
    public static UUID tenantId() {
        TenantContext.Current c = TenantContext.require();
        if (!"STORE".equals(c.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "No store for this host");
        return c.id();
    }

    /** For customer-facing writes (checkout, registration): the store must be open for business. */
    public static UUID openTenantId() {
        UUID id = tenantId();
        if (!OPEN.contains(TenantContext.get().status()))
            throw BusinessException.forbidden("STORE_UNAVAILABLE", "This store is not accepting orders right now");
        return id;
    }
}
