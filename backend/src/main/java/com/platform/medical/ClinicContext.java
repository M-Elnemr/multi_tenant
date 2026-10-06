package com.platform.medical;

import com.platform.shared.BusinessException;
import com.platform.shared.TenantContext;
import java.util.Set;
import java.util.UUID;

/** Guards for clinic endpoints: the Host must resolve to a CLINIC tenant (and, for patient-facing writes, one that is open). */
public final class ClinicContext {
    private static final Set<String> OPEN = Set.of("TRIAL", "ACTIVE", "PAST_DUE");

    private ClinicContext() {}

    public static UUID tenantId() {
        TenantContext.Current c = TenantContext.require();
        if (!"CLINIC".equals(c.type())) throw BusinessException.notFound("TENANT_NOT_FOUND", "No clinic for this host");
        return c.id();
    }

    public static UUID openTenantId() {
        UUID id = tenantId();
        if (!OPEN.contains(TenantContext.get().status()))
            throw BusinessException.forbidden("CLINIC_UNAVAILABLE", "This clinic is not accepting bookings right now");
        return id;
    }
}
