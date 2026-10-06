package com.platform.commerce;

import java.util.UUID;

public final class OrderEvents {
    private OrderEvents() {}

    public record OrderCreated(UUID tenantId, UUID orderId, UUID userId, String orderNumber) {}

    public record OrderStatusChanged(UUID tenantId, UUID orderId, UUID userId, String orderNumber, String newStatus) {}
}
