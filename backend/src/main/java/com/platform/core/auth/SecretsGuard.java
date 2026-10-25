package com.platform.core.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The repository carries development defaults for the token-signing and webhook secrets so a fresh checkout runs. They are public, so a server that still uses one would let anyone
 * mint a login token or fake a payment webhook: outside the dev and test profiles the application refuses to start with them.
 */
@Component
public class SecretsGuard {
    private static final String DEV_JWT = "Q0hBTkdFX01FX2Rldl9vbmx5X3NlY3JldF9DSEFOR0VfTUVfZGV2X29ubHk=";
    private static final String DEV_WEBHOOK = "dev-only-webhook-secret-change-me";

    public SecretsGuard(Environment env, @Value("${app.jwt.secret}") String jwtSecret, @Value("${app.billing.webhook-secret}") String webhookSecret) {
        if (env.acceptsProfiles(Profiles.of("dev", "test", "local"))) return;
        List<String> bad = new java.util.ArrayList<>();
        if (DEV_JWT.equals(jwtSecret)) bad.add("JWT_SECRET");
        if (DEV_WEBHOOK.equals(webhookSecret)) bad.add("BILLING_WEBHOOK_SECRET");
        if (!bad.isEmpty()) throw new IllegalStateException("Refusing to start with the public development default for " + String.join(", ", bad) + ": set a real value (openssl rand -base64 32)");
    }
}
