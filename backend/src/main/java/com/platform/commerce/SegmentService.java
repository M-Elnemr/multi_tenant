package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.chat.MetaChannelService;
import com.platform.chat.MetaGraph;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Customer segments computed from orders (nothing to maintain) and WhatsApp broadcasts to them. A broadcast goes only to customers who agreed to WhatsApp
 * contact AND whose 24 hour window is open, so it never costs money; everyone else is counted as skipped and the owner can reach them by hand.
 */
@Service
public class SegmentService {
    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);
    public static final Set<String> KEYS = Set.of("NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED");

    private final JdbcClient jdbc;
    private final MetaChannelService channels;
    private final MetaGraph graph;
    private final AuditService audit;

    public SegmentService(JdbcClient jdbc, MetaChannelService channels, MetaGraph graph, AuditService audit) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.graph = graph;
        this.audit = audit;
    }



    public Map<String, Object> overview(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED")) out.put(k, 0L);
        long withWa = 0;
        for (var r : jdbc.sql(SegmentSql.BASE + "SELECT segment, count(*) AS n, count(*) FILTER (WHERE wa IS NOT NULL) AS wa FROM s GROUP BY segment").param("t", tenantId).query().listOfRows()) {
            out.put((String) r.get("segment"), ((Number) r.get("n")).longValue());
            withWa += ((Number) r.get("wa")).longValue();
        }
        return Map.of("segments", out, "withWhatsappConsent", withWa);
    }

    public List<Map<String, Object>> customers(UUID tenantId, String segment) {
        requireKey(segment);
        return Rows.camel(jdbc.sql(SegmentSql.BASE + "SELECT phone, name, orders, delivered, spent, last_order, (wa IS NOT NULL) AS whatsapp_consent FROM s WHERE segment = :g ORDER BY spent DESC LIMIT 200")
                .param("t", tenantId).param("g", segment).query().listOfRows());
    }

    private static void requireKey(String segment) { if (segment == null || !KEYS.contains(segment)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown segment"); }

    /** How many people a broadcast would reach right now, for free. */
    public Map<String, Object> preview(UUID tenantId, String segment) {
        requireKey(segment);
        var rows = jdbc.sql(SegmentSql.BASE + "SELECT wa FROM s WHERE segment = :g").param("t", tenantId).param("g", segment).query().listOfRows();
        long consent = rows.stream().filter(r -> r.get("wa") != null).count(), open = 0;
        for (var r : rows) if (r.get("wa") != null && windowOpen(tenantId, digits((String) r.get("wa")), Instant.now())) open++;
        return Map.of("audience", rows.size(), "withConsent", consent, "reachableNow", open);
    }

    private static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private boolean windowOpen(UUID tenantId, String number, Instant now) {
        return jdbc.sql("SELECT count(*) FROM chat.wa_windows WHERE tenant_id = :t AND number = :n AND last_inbound_at > :s").param("t", tenantId).param("n", number)
                .param("s", Timestamp.from(now.minus(Duration.ofHours(24)))).query(Long.class).single() > 0;
    }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, String segment, String message) {
        requireKey(segment);
        String text = message == null ? "" : message.trim();
        if (text.length() < 5 || text.length() > 800) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 5 to 800 characters");
        var ch = channels.whatsappChannel(tenantId);
        if (ch == null) throw BusinessException.badRequest("NO_WHATSAPP", "Connect your WhatsApp number first");
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND created_at > now() - interval '24 hours'").param("t", tenantId).query(Long.class).single() >= 1)
            throw BusinessException.conflict("BROADCAST_LIMIT", "One broadcast a day, to protect your WhatsApp number from being reported as spam");
        var rows = jdbc.sql(SegmentSql.BASE + "SELECT phone, name, wa FROM s WHERE segment = :g LIMIT 1000").param("t", tenantId).param("g", segment).query().listOfRows();
        int sent = 0, noWindow = 0, noConsent = 0;
        Instant now = Instant.now();
        for (var r : rows) {
            if (r.get("wa") == null) { noConsent++; continue; }
            String to = digits((String) r.get("wa"));
            if (!windowOpen(tenantId, to, now)) { noWindow++; continue; }
            String first = r.get("name") == null ? "" : ((String) r.get("name")).trim().split("\\s+")[0];
            try {
                graph.post(ch.phoneId() + "/messages", ch.token(), Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text.replace("{name}", first))));
                sent++;
            } catch (MetaGraph.MetaException e) {
                log.warn("Broadcast message failed: {}", e.getMessage());
            }
        }
        UUID id = jdbc.sql("INSERT INTO commerce.broadcasts (tenant_id, segment, message, audience, sent_count, skipped_window, skipped_consent, created_by) VALUES (:t, :s, :m, :a, :sent, :w, :c, :u) RETURNING id")
                .param("t", tenantId).param("s", segment).param("m", text).param("a", rows.size()).param("sent", sent).param("w", noWindow).param("c", noConsent).param("u", actor).query(UUID.class).single();
        audit.record(actor, tenantId, "BROADCAST_SENT", "broadcast", id, "{\"segment\":\"" + segment + "\"}");
        return Map.of("id", id, "audience", rows.size(), "sent", sent, "skippedNoWindow", noWindow, "skippedNoConsent", noConsent);
    }

    public List<Map<String, Object>> history(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, segment, message, audience, sent_count, skipped_window, skipped_consent, created_at FROM commerce.broadcasts WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 30").param("t", tenantId).query().listOfRows());
    }
}
