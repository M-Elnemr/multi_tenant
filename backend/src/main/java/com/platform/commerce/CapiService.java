package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.chat.MetaGraph;
import com.platform.chat.TokenCrypto;
import com.platform.shared.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends each new order to Meta from the server (Conversions API), so purchases are counted even when the browser blocks the pixel. Needs the shop's Pixel ID and a token; off otherwise. */
@Service
public class CapiService {
    private static final Logger log = LoggerFactory.getLogger(CapiService.class);

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final TokenCrypto crypto;
    private final AuditService audit;
    private final ExecutorService pool;

    public CapiService(JdbcClient jdbc, MetaGraph graph, TokenCrypto crypto, AuditService audit, @Value("${app.meta.async:true}") boolean async) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.crypto = crypto;
        this.audit = audit;
        this.pool = async ? Executors.newFixedThreadPool(2) : null;
    }

    public Map<String, Object> settings(UUID tenantId) {
        var r = jdbc.sql("SELECT capi_enabled, capi_token_enc IS NOT NULL AS has_token, last_status FROM commerce.tracking_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        String pixel = jdbc.sql("SELECT meta_pixel_id FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r != null && Boolean.TRUE.equals(r.get("capi_enabled")));
        m.put("hasToken", r != null && Boolean.TRUE.equals(r.get("has_token")));
        m.put("pixelId", pixel == null || pixel.isBlank() ? null : pixel);
        m.put("lastStatus", r == null ? null : r.get("last_status"));
        return m;
    }

    public Map<String, Object> save(UUID tenantId, UUID actor, Boolean enabled, String token) {
        var cur = settings(tenantId);
        boolean e = enabled != null ? enabled : (Boolean) cur.get("enabled");
        String t = token == null ? null : token.trim();
        if (t != null && !t.isEmpty() && (t.length() < 20 || t.length() > 1000)) throw BusinessException.badRequest("VALIDATION_ERROR", "The access token looks wrong");
        if (e && cur.get("pixelId") == null) throw BusinessException.badRequest("PIXEL_REQUIRED", "Add your Meta Pixel ID in the store settings first");
        boolean hasToken = (Boolean) cur.get("hasToken") || (t != null && !t.isEmpty());
        if (e && !hasToken) throw BusinessException.badRequest("TOKEN_REQUIRED", "Add the Conversions API access token first");
        jdbc.sql("INSERT INTO commerce.tracking_settings (tenant_id, capi_enabled, capi_token_enc) VALUES (:t, :e, :k) ON CONFLICT (tenant_id) DO UPDATE SET capi_enabled = :e, capi_token_enc = coalesce(:k, commerce.tracking_settings.capi_token_enc), updated_at = now()")
                .param("t", tenantId).param("e", e).param("k", t == null || t.isEmpty() ? null : crypto.encrypt(t)).update();
        audit.record(actor, tenantId, "SETTINGS_CHANGED", "capi", null, null);
        return settings(tenantId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCreated(OrderEvents.OrderCreated e) {
        Runnable task = () -> { try { send(e.tenantId(), e.orderId()); } catch (RuntimeException ex) { log.warn("CAPI event failed: {}", ex.getMessage()); } };
        if (pool != null) pool.submit(task); else task.run();
    }

    /** Public for tests. Returns true when an event was sent. */
    public boolean send(UUID tenantId, UUID orderId) {
        var s = jdbc.sql("SELECT capi_enabled, capi_token_enc FROM commerce.tracking_settings WHERE tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (s == null || !Boolean.TRUE.equals(s.get("capi_enabled")) || s.get("capi_token_enc") == null) return false;
        String pixel = jdbc.sql("SELECT meta_pixel_id FROM commerce.store_profiles WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElse(null);
        if (pixel == null || pixel.isBlank()) return false;
        var o = jdbc.sql("SELECT order_number, total_minor, currency, customer_phone_snapshot, created_at, status FROM commerce.orders WHERE id = :o AND tenant_id = :t").param("o", orderId).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (o == null) return false;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", "Purchase");
        event.put("event_time", ((java.sql.Timestamp) o.get("created_at")).toInstant().getEpochSecond());
        event.put("event_id", "order-" + o.get("order_number"));
        event.put("action_source", "website");
        event.put("user_data", Map.of("ph", List.of(sha256(((String) o.get("customer_phone_snapshot")).replaceAll("\\D", "")))));
        event.put("custom_data", Map.of("currency", o.get("currency"), "value", ((Number) o.get("total_minor")).longValue() / 100.0, "order_id", o.get("order_number")));
        String status;
        try {
            graph.post(pixel.trim() + "/events", crypto.decrypt((String) s.get("capi_token_enc")), Map.of("data", List.of(event)));
            status = "OK";
        } catch (MetaGraph.MetaException ex) {
            status = "Meta said: " + (ex.getMessage() == null ? "error" : ex.getMessage().substring(0, Math.min(150, ex.getMessage().length())));
        }
        jdbc.sql("UPDATE commerce.tracking_settings SET last_status = :s WHERE tenant_id = :t").param("s", status).param("t", tenantId).update();
        return "OK".equals(status);
    }

    private static String sha256(String s) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
