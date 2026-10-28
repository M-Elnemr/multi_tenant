package com.platform.commerce;

import java.util.UUID;

public final class OrderEvents {
    private OrderEvents() {}

    public record OrderCreated(UUID tenantId, UUID orderId, UUID userId, String orderNumber) {}

    public record OrderStatusChanged(UUID tenantId, UUID orderId, UUID userId, String orderNumber, String newStatus) {}

    /** The customer themselves cancelled the order (not staff, not the payment-expiry job). */
    public record OrderCancelledByCustomer(UUID tenantId, UUID orderId, String orderNumber) {}
    /** The customer asked to cancel in the chat and gave a reason: the order is kept until the shop talks to them. */
    public record OrderCancelRequested(UUID tenantId, UUID orderId, String orderNumber, String reason) {}
}
