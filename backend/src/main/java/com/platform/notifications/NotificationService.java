package com.platform.notifications;

import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final List<NotificationChannel> channels;

    public NotificationService(JdbcClient jdbc, List<NotificationChannel> channels) {
        this.jdbc = jdbc;
        this.channels = channels;
    }

    /** In-app notification, plus an e-mail in the outbox when the user has an address and email=true. */
    @Transactional
    public void notify(UUID userId, UUID tenantId, String type, String title, String body, String payloadJson, boolean email) {
        jdbc.sql("INSERT INTO notifications.notifications (user_id, tenant_id, notification_type, title, body, payload) VALUES (:u, :t, :ty, :ti, :b, CAST(:p AS jsonb))")
                .param("u", userId).param("t", tenantId).param("ty", type).param("ti", title).param("b", body).param("p", payloadJson).update();
        if (email) {
            jdbc.sql("""
                    INSERT INTO notifications.outbox (user_id, tenant_id, channel, to_address, subject, body)
                    SELECT :u, :t, 'EMAIL', u.email, :s, :b FROM core.users u WHERE u.id = :u AND u.email IS NOT NULL AND u.status = 'ACTIVE'
                    """).param("u", userId).param("t", tenantId).param("s", title).param("b", body).update();
        }
    }

    /** Phone push: one outbox row per registered device of this person (sent by PushChannel, with retry). */
    @Transactional
    public void push(UUID principalId, UUID tenantId, String title, String body) {
        jdbc.sql("""
                INSERT INTO notifications.outbox (user_id, tenant_id, channel, to_address, subject, body)
                SELECT :u, :t, 'PUSH', token, :s, :b FROM notifications.device_tokens WHERE principal_id = :u
                """).param("u", principalId).param("t", tenantId).param("s", title).param("b", body).update();
    }

    @Transactional
    public void registerDevice(UUID principalId, String token, String platform) {
        if (token == null || token.isBlank() || token.length() > 512) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid device token");
        jdbc.sql("""
                INSERT INTO notifications.device_tokens (principal_id, token, platform) VALUES (:u, :t, :p)
                ON CONFLICT (token) DO UPDATE SET principal_id = :u, platform = :p, last_seen_at = now()
                """).param("u", principalId).param("t", token).param("p", platform == null || platform.isBlank() ? "ANDROID" : platform.toUpperCase()).update();
    }

    @Transactional
    public void unregisterDevice(UUID principalId, String token) {
        jdbc.sql("DELETE FROM notifications.device_tokens WHERE token = :t AND principal_id = :u").param("t", token).param("u", principalId).update();
    }

    /** Background sender with retry; a failing provider only delays messages, never the business action that caused them. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    public void dispatchOutbox() {
        var batch = jdbc.sql("SELECT id, channel, to_address, subject, body, attempts FROM notifications.outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT 50").query().listOfRows();
        for (var m : batch) {
            UUID id = (UUID) m.get("id");
            try {
                NotificationChannel ch = channels.stream().filter(c -> c.code().equals(m.get("channel"))).findFirst().orElseThrow(() -> new IllegalStateException("No channel " + m.get("channel")));
                ch.send((String) m.get("to_address"), (String) m.get("subject"), (String) m.get("body"));
                jdbc.sql("UPDATE notifications.outbox SET status = 'SENT', sent_at = now(), attempts = attempts + 1 WHERE id = :i").param("i", id).update();
            } catch (Exception e) {
                int attempts = ((Number) m.get("attempts")).intValue() + 1;
                log.warn("Outbox message {} failed (attempt {}): {}", id, attempts, e.getMessage());
                jdbc.sql("UPDATE notifications.outbox SET attempts = :a, last_error = :e, status = CASE WHEN :a >= :max THEN 'FAILED' ELSE 'PENDING' END WHERE id = :i")
                        .param("a", attempts).param("e", String.valueOf(e.getMessage())).param("max", MAX_ATTEMPTS).param("i", id).update();
            }
        }
    }

    // ---- inbox API --------------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID userId, UUID tenantId, boolean unreadOnly, Page page) {
        long total = jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE user_id = :u AND (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid)) AND (NOT :un OR read_at IS NULL)")
                .param("u", userId).param("t", tenantId).param("un", unreadOnly).query(Long.class).single();
        var rows = jdbc.sql("SELECT id, notification_type, title, body, payload::text AS payload, read_at, created_at FROM notifications.notifications WHERE user_id = :u AND (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid)) AND (NOT :un OR read_at IS NULL) ORDER BY created_at DESC LIMIT :lim OFFSET :off")
                .param("u", userId).param("t", tenantId).param("un", unreadOnly).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        List<Map<String, Object>> data = rows.stream().map(r -> {
            Map<String, Object> m = Rows.camel(r);
            m.put("payload", Rows.json(r.get("payload")));
            return m;
        }).toList();
        return page.wrap(data, total);
    }

    public long unreadCount(UUID userId, UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE user_id = :u AND read_at IS NULL AND (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid))").param("u", userId).param("t", tenantId).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        if (jdbc.sql("UPDATE notifications.notifications SET read_at = coalesce(read_at, now()) WHERE id = :i AND user_id = :u").param("i", id).param("u", userId).update() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Notification not found");
    }

    @Transactional
    public void markAllRead(UUID userId, UUID tenantId) {
        jdbc.sql("UPDATE notifications.notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL AND (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid))").param("u", userId).param("t", tenantId).update();
    }
}
