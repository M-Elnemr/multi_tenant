package com.platform.core.onboarding;

import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import java.util.UUID;

/**
 * Extension point: the commerce and medical modules implement this to seed type-specific defaults
 * (default branch, shipping, doctor profile, schedule...) inside the onboarding transaction, so a new
 * tenant is usable immediately without core knowing about either module.
 */
public interface TenantProvisioner {
    TenantType supports();

    void provision(Tenant tenant, UUID ownerUserId);
}
