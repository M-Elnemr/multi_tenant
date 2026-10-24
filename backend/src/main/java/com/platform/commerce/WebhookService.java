package com.platform.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditService;
import com.platform.chat.TokenCrypto;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.UrlGuard;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the shop's own systems when something happens (order.created, order.status_changed). Each call is signed with the webhook's secret
 * ({@code X-Webhook-Signature: sha256=...} over the body), retried with growing delays up to 6 times, and only goes to public https addresses.
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Set<String> EVENTS = Set.of("order.created", "order.status_changed");
    static final int MAX_ATTEMPTS = 6;

    private final JdbcClient jdbc;
    private final HttpGateway http;
    private final TokenCrypto crypto;
    private final AuditService audit;
    private final boolean scheduler;

    public WebhookService(JdbcClient jdbc, HttpGateway http, TokenCrypto crypto, AuditService audit, @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.http = http;
        this.crypto = crypto;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    public Map<String, Object> create(UUID t, UUID actor, String url, List<String> events) {
        String clean;
        try { clean = UrlGuard.requirePublicHttps(url); } catch (HttpGateway.ApiException e) { throw BusinessException.badRequest("VALIDATION_ERROR", e.getMessage()); }
        String full = url.trim().startsWith("http") ? url.trim() : "https://" + url.trim();
        if (full.length() > 480) throw BusinessException.badRequest("VALIDATION_ERROR", "The address is too long");
        List<String> ev = events == null || events.isEmpty() ? List.of("order.created", "order.status_changed") : events;
        if (!EVENTS.containsAll(ev)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown event");
        if (jdbc.sql("SELECT count(*) FROM commerce.webhooks WHERE tenant_id = :t").param("t", t).query(Long.class).single() >= 10) throw BusinessException.conflict("TOO_MANY_WEBHOOKS", "Delete a webhook you no longer use first");
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        StringBuilder secret = new StringBuilder("whsec_");
        for (byte b : raw) secret.append(String.format("%02x", b));
        UUID id = jdbc.sql("INSERT INTO commerce.webhooks (tenant_id, url, secret_enc, events) VALUES (:t, :u, :s, CAST(:e AS text[])) RETURNING id").param("t", t).param("u", full).param("s", crypto.encrypt(secret.toString()))
                .param("e", "{" + String.join(",", ev) + "}").query(UUID.class).single();
        audit.record(actor, t, "WEBHOOK_CREATED", "webhook", id, null);
        return Map.of("id", id, "url", full, "events", ev, "secret", secret.toString(), "note", "Copy the secret now. It is not shown again.");
    }

    public List<Map<String, Object>> list(UUID t) {
        return jdbc.sql("""
                SELECT w.id, w.url, array_to_string(w.events, ',') AS events, w.active, w.created_at,
                       (SELECT status FROM commerce.webhook_deliveries d WHERE d.webhook_id = w.id ORDER BY d.id DESC LIMIT 1) AS last_status,
                       (SELECT count(*) FROM commerce.webhook_deliveries d WHERE d.webhook_id = w.id AND d.status = 'FAILED') AS failed
                FROM commerce.webhooks w WHERE w.tenant_id = :t ORDER BY w.created_at DESC
                """).param("t", t).query().listOfRows().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            m.put("events", ((String) r.get("events")).isEmpty() ? List.of() : List.of(((String) r.get("events")).split(",")));
            return m;
        }).toList();
    }

    public void delete(UUID t, UUID actor, UUID id) {
        if (jdbc.sql("DELETE FROM commerce.webhooks WHERE id = :i AND tenant_id = :t").param("i", id).param("t", t).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Webhook not found");
        audit.record(actor, t, "WEBHOOK_DELETED", "webhook", id, null);
    }

    public Map<String, Object> setActive(UUID t, UUID id, boolean active) {
        if (jdbc.sql("UPDATE commerce.webhooks SET active = :a WHERE id = :i AND tenant_id = :t").param("a", active).param("i", id).param("t", t).update() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Webhook not found");
        return Map.of("active", active);
    }

    public List<Map<String, Object>> deliveries(UUID t, UUID webhookId) {
        return Rows.camel(jdbc.sql("SELECT id, event, status, attempts, response_code, last_error, created_at, sent_at FROM commerce.webhook_deliveries WHERE tenant_id = :t AND webhook_id = :w ORDER BY id DESC LIMIT 30").param("t", t).param("w", webhookId).query().listOfRows());
    }

    // ---- events -------------------------------------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(OrderEvents.OrderCreated e) { enqueue(e.tenantId(), "order.created", e.orderId()); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatus(OrderEvents.OrderStatusChanged e) { enqueue(e.tenantId(), "order.status_changed", e.orderId()); }

    private void enqueue(UUID t, String event, UUID orderId) {
        try {
            var hooks = jdbc.sql("SELECT id FROM commerce.webhooks WHERE tenant_id = :t AND active AND CAST(:e AS text) = ANY (events)").param("t", t).param("e", event).query(UUID.class).list();
            if (hooks.isEmpty()) return;
            var o = jdbc.sql("SELECT id, order_number, status, payment_status, payment_method, currency, total_minor, customer_name_snapshot, customer_phone_snapshot, governorate_code, created_at FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", t).query().listOfRows().stream().findFirst().orElse(null);
            if (o == null) return;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("order", Rows.camel(o));
            payload.put("items", Rows.camel(jdbc.sql("SELECT sku_snapshot AS sku, product_name_snapshot AS name, quantity, unit_price_minor FROM commerce.order_items WHERE order_id = :o").param("o", orderId).query().listOfRows()));
            String body = JSON.writeValueAsString(payload);
            for (UUID h : hooks) jdbc.sql("INSERT INTO commerce.webhook_deliveries (webhook_id, tenant_id, event, payload) VALUES (:w, :t, :e, :p)").param("w", h).param("t", t).param("e", event).param("p", body).update();
        } catch (Exception ex) {
            log.warn("Could not queue webhook {}: {}", event, ex.getMessage());
        }
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder b = new StringBuilder("sha256=");
            for (byte x : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 40_000)
    public void job() {
        if (!scheduler) return;
        try { deliverDue(Instant.now()); } catch (RuntimeException e) { log.warn("Webhook run failed", e); }
    }

    /** Sends what is due; returns how many calls were answered with 2xx. Public for tests. */
    public int deliverDue(Instant now) {
        int ok = 0;
        for (var d : jdbc.sql("SELECT d.id, d.webhook_id, d.event, d.payload, d.attempts, w.url, w.secret_enc FROM commerce.webhook_deliveries d JOIN commerce.webhooks w ON w.id = d.webhook_id WHERE d.status = 'PENDING' AND d.next_attempt_at <= :n AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = w.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) ORDER BY d.id LIMIT 100")
                .param("n", Timestamp.from(now)).query().listOfRows()) {
            long id = ((Number) d.get("id")).longValue();
            int attempts = ((Number) d.get("attempts")).intValue() + 1;
            int code = 0;
            String error = null;
            try {
                UrlGuard.requirePublicHttps((String) d.get("url"));
                String body = (String) d.get("payload");
                var res = http.send(new HttpGateway.Req("POST", (String) d.get("url"), Map.of("Content-Type", "application/json", "X-Webhook-Event", (String) d.get("event"), "X-Webhook-Signature", sign(crypto.decrypt((String) d.get("secret_enc")), body)), body, "webhook", Duration.ofSeconds(10)));
                code = res.status();
                if (!res.ok()) error = "HTTP " + code;
            } catch (RuntimeException e) {
                error = e.getMessage() == null ? "error" : e.getMessage();
            }
            if (error == null) {
                jdbc.sql("UPDATE commerce.webhook_deliveries SET status = 'SENT', attempts = :a, response_code = :c, sent_at = now(), last_error = NULL WHERE id = :i").param("a", attempts).param("c", code).param("i", id).update();
                ok++;
            } else if (attempts >= MAX_ATTEMPTS) {
                jdbc.sql("UPDATE commerce.webhook_deliveries SET status = 'FAILED', attempts = :a, response_code = :c, last_error = :e WHERE id = :i").param("a", attempts).param("c", code == 0 ? null : code).param("e", clip(error)).param("i", id).update();
            } else {
                long delay = (long) Math.pow(4, attempts) * 15;   // 1 min, 4 min, 16 min, ... (seconds: 60, 240, 960 ...)
                jdbc.sql("UPDATE commerce.webhook_deliveries SET attempts = :a, response_code = :c, last_error = :e, next_attempt_at = :n WHERE id = :i").param("a", attempts).param("c", code == 0 ? null : code).param("e", clip(error)).param("n", Timestamp.from(now.plusSeconds(delay))).param("i", id).update();
            }
        }
        jdbc.sql("DELETE FROM commerce.webhook_deliveries WHERE created_at < now() - interval '14 days'").update();
        return ok;
    }

    private static String clip(String s) { return s.length() > 280 ? s.substring(0, 280) : s; }
}
