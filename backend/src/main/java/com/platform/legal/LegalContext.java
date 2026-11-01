package com.platform.legal;

import com.platform.shared.BusinessException;
import com.platform.shared.TenantContext;
import java.util.Set;
import java.util.UUID;

/** Guards for lawyer endpoints: the Host must resolve to a LAWYER tenant (and, for visitor bookings, one that is open). */
public final class LegalContext {
    private static final Set<String> OPEN = Set.of("TRIAL", "ACTIVE", "PAST_DUE");

    private LegalContext() {}

    public static UUID tenantId() {
        TenantContext.Current c = TenantContext.require();
        if (!"LAWYER".equals(c.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "No lawyer for this host");
        return c.id();
    }

    public static UUID openTenantId() {
        UUID id = tenantId();
        if (!OPEN.contains(TenantContext.get().status())) throw BusinessException.forbidden("LAWYER_UNAVAILABLE", "This office is not taking bookings right now");
        return id;
    }
}
