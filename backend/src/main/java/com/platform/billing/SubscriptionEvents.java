package com.platform.billing;

import java.util.UUID;

public final class SubscriptionEvents {
    private SubscriptionEvents() {}

    /** status is the new tenant-facing state: PAST_DUE, SUSPENDED or CANCELLED. */
    public record StateChanged(UUID tenantId, String status) {}
}
