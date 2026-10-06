package com.platform.billing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** UNIQUE(provider, event_id) de-duplication shared by every module that receives provider webhooks (spec 55). */
@Service
public class WebhookEventStore {
    private final JdbcClient jdbc;

    public WebhookEventStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** True the first time an event is seen; false for a repeated delivery. */
    public boolean firstDelivery(String provider, String eventId, String rawBody) {
        return jdbc.sql("""
                INSERT INTO billing.webhook_events (provider, event_id, signature_verified, payload_json, status)
                VALUES (:p, :e, TRUE, CAST(:b AS jsonb), 'RECEIVED') ON CONFLICT (provider, event_id) DO NOTHING
                """).param("p", provider).param("e", eventId).param("b", rawBody).update() == 1;
    }

    public void markProcessed(String provider, String eventId, String status) {
        jdbc.sql("UPDATE billing.webhook_events SET status = :s, processed_at = now() WHERE provider = :p AND event_id = :e")
                .param("s", status).param("p", provider).param("e", eventId).update();
    }
}
