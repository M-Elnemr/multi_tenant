package com.platform.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Development/test provider: no money moves; webhooks are HMAC-SHA256 signed with a shared secret. */
@Component
public class MockPaymentProvider implements PaymentProvider {
    private final String secret;

    public MockPaymentProvider(@Value("${app.billing.webhook-secret}") String secret) { this.secret = secret; }

    @Override public String code() { return "mock"; }

    @Override
    public Intent createPaymentIntent(UUID tenantId, UUID invoiceId, long amountMinor, String currency, String idempotencyKey) {
        String id = "mock_" + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        return new Intent(id, "/billing/mock-checkout/" + id);
    }

    @Override
    public boolean verifySignature(String rawBody, String header) {
        if (header == null) return false;
        return MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
