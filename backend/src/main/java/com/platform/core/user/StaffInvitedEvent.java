package com.platform.core.user;

import java.util.UUID;

/** Published when someone is added to a tenant's staff, so modules can create their own per-role records (e.g. a doctor profile). */
public record StaffInvitedEvent(UUID tenantId, UUID userId, String role) {}
