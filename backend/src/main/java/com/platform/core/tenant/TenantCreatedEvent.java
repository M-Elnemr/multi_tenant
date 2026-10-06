package com.platform.core.tenant;

import java.util.UUID;

/** Published inside the onboarding transaction so other modules (billing, ...) can set up their own data. */
public record TenantCreatedEvent(UUID tenantId, TenantType type, UUID ownerUserId) {}
