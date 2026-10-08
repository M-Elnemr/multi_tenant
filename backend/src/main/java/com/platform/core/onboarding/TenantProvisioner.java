package com.platform.core.onboarding;

import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extension point: the commerce and medical modules implement this to seed type-specific defaults
 * (default branch, shipping, doctor profile, schedule...) inside the onboarding transaction, so a new
 * tenant is usable immediately without core knowing about either module.
 */
public interface TenantProvisioner {
    TenantType supports();

    void provision(Tenant tenant, UUID ownerUserId);

    /** Provisioners that use the owner's category choice override this; the default ignores it. */
    default void provision(Tenant tenant, UUID ownerUserId, ProvisionOptions options) {
        provision(tenant, ownerUserId);
    }

    /** The list offered at sign-up (code, nameAr, nameEn, popular), most common first, "other" last. Empty if this type has none. */
    default List<Map<String, Object>> categories() {
        return List.of();
    }
}
