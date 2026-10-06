package com.platform.billing;

import java.util.UUID;

/** Provider-agnostic payment abstraction (spec 8). Real gateways plug in as additional beans. */
public interface PaymentProvider {
    String code();

    record Intent(String providerPaymentId, String checkoutUrl) {}

    Intent createPaymentIntent(UUID tenantId, UUID invoiceId, long amountMinor, String currency, String idempotencyKey);

    /** Validates a webhook signature over the raw body. */
    boolean verifySignature(String rawBody, String signatureHeader);
}
