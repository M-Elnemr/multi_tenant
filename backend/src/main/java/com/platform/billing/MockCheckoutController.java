package com.platform.billing;

import com.platform.commerce.OrderService;
import com.platform.medical.AppointmentService;
import com.platform.shared.BusinessException;
import com.platform.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Development-only stand-in for a payment gateway's hosted checkout: "pays" an invoice/order/appointment by delivering the same
 * signed webhook a real provider would. Exists only when app.dev.mock-checkout=true (never in production).
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "app.dev.mock-checkout", havingValue = "true")
public class MockCheckoutController {
    public record PayRequest(String kind, UUID id, long amountMinor) {}

    private final BillingService billing;
    private final MockPaymentProvider provider;
    private final OrderService orders;
    private final AppointmentService appointments;

    public MockCheckoutController(BillingService billing, MockPaymentProvider provider, OrderService orders, AppointmentService appointments) {
        this.billing = billing;
        this.provider = provider;
        this.orders = orders;
        this.appointments = appointments;
    }

    @PostMapping("/mock-pay")
    public Map<String, Object> pay(@RequestBody PayRequest r, Authentication a) {
        UUID tenantId = TenantContext.require().id();
        return switch (r.kind()) {
            case "subscription" -> {
                String body = "{\"eventId\":\"mock-" + UUID.randomUUID() + "\",\"type\":\"payment.succeeded\",\"invoiceId\":\"" + r.id() + "\"}";
                yield Map.of("result", billing.handleWebhook("mock", body, provider.sign(body)).name());
            }
            case "order" -> Map.of("result", orders.markCardPaid(tenantId, r.id(), r.amountMinor(), "mock") ? "PROCESSED" : "DUPLICATE");
            case "appointment" -> Map.of("result", appointments.markCardPaid(tenantId, r.id(), r.amountMinor()) ? "PROCESSED" : "DUPLICATE");
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown kind");
        };
    }
}
