package com.platform.core.auth;

import java.util.UUID;

/** Lets a module decide that a valid global account may become a member of a tenant on first login (e.g. store shoppers). */
public interface TenantAutoJoin {
    boolean supports(String tenantType);

    void join(UUID userId, UUID tenantId);
}
