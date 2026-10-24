package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.WalletService;
import com.platform.chat.EvolutionService;
import com.platform.chat.MetaChannelService;
import com.platform.chat.MetaGraph;
import com.platform.notifications.NotificationService;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.Gender;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Customer segments computed from orders (nothing to maintain) and broadcasts to them. A broadcast can go three ways, alone or together:
 * FREE (the shop's own linked WhatsApp number, paced), PAID (official WhatsApp: each message is charged from the shop's prepaid credit, and goes only to customers who agreed
 * and whose 24 hour window is open), and a phone notification to customers who installed the app. Messages are queued and sent by a job so a long list never blocks the screen.
 */
@Service
public class SegmentService {
    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);
    public static final Set<String> KEYS = Set.of("ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED");
    static final int PAID_PER_PASS = 20;

    public record Req(String segment, String message, UUID imageFileId, String mode, Boolean push, String pushTitle, Boolean onlyConsented) {}

    private final JdbcClient jdbc;
    private final MetaChannelService channels;
    private final MetaGraph graph;
    private final AuditService audit;
    private final EvolutionService evolution;
    private final WalletService wallet;
    private final NotificationService notifications;
    private final OpenUrls urls;
    private final long priceMinor;
    private final boolean scheduler;
    private final int gapSeconds;

    public SegmentService(JdbcClient jdbc, MetaChannelService channels, MetaGraph graph, AuditService audit, EvolutionService evolution, WalletService wallet, NotificationService notifications, OpenUrls urls,
                          @Value("${app.whatsapp.broadcast-price-minor:300}") long priceMinor, @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler, @Value("${app.broadcast.free-gap-seconds:10}") int gapSeconds) {
        this.gapSeconds = Math.max(0, gapSeconds);
        this.jdbc = jdbc;
        this.channels = channels;
        this.graph = graph;
        this.audit = audit;
        this.evolution = evolution;
        this.wallet = wallet;
        this.notifications = notifications;
        this.urls = urls;
        this.priceMinor = priceMinor;
        this.scheduler = scheduler;
    }

    public Map<String, Object> overview(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED")) out.put(k, 0L);
        long withWa = 0, all = 0;
        for (var r : jdbc.sql(SegmentSql.BASE + "SELECT segment, count(*) AS n, count(*) FILTER (WHERE wa IS NOT NULL) AS wa FROM s GROUP BY segment").param("t", tenantId).query().listOfRows()) {
            long n = ((Number) r.get("n")).longValue();
            out.put((String) r.get("segment"), n);
            all += n;
            withWa += ((Number) r.get("wa")).longValue();
        }
        out.put("ALL", all);
        return Map.of("segments", out, "withWhatsappConsent", withWa, "messagePriceMinor", priceMinor, "balanceMinor", wallet.balance(tenantId));
    }

    public List<Map<String, Object>> customers(UUID tenantId, String segment) {
        requireKey(segment);
        return Rows.camel(jdbc.sql(SegmentSql.BASE + "SELECT phone, name, orders, delivered, spent, last_order, (wa IS NOT NULL) AS whatsapp_consent, (uid IS NOT NULL) AS has_account FROM s WHERE (:g = 'ALL' OR segment = :g) ORDER BY spent DESC LIMIT 300")
                .param("t", tenantId).param("g", segment).query().listOfRows());
    }

    private static void requireKey(String segment) { if (segment == null || !KEYS.contains(segment)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown segment"); }

    /** How many people each way of sending would reach right now. */
    public Map<String, Object> preview(UUID tenantId, String segment) {
        requireKey(segment);
        var rows = jdbc.sql(SegmentSql.BASE + "SELECT phone, wa, uid FROM s WHERE (:g = 'ALL' OR segment = :g)").param("t", tenantId).param("g", segment).query().listOfRows();
        long consent = rows.stream().filter(r -> r.get("wa") != null).count(), open = 0, app = 0;
        for (var r : rows) {
            if (r.get("wa") != null && windowOpen(tenantId, digits((String) r.get("wa")), Instant.now())) open++;
            if (r.get("uid") != null && hasDevice((UUID) r.get("uid"))) app++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("audience", rows.size());
        m.put("withConsent", consent);
        m.put("reachableNow", open);       // official WhatsApp (paid): agreed + window open
        m.put("freeReachable", rows.stream().filter(r -> digits((String) r.get("phone")).length() >= 8).count());   // the shop's own number can message anyone it has a phone for
        m.put("appUsers", app);
        return m;
    }

    private boolean hasDevice(UUID user) { return jdbc.sql("SELECT EXISTS (SELECT 1 FROM notifications.device_tokens WHERE principal_id = :u)").param("u", user).query(Boolean.class).single(); }

    private static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private boolean windowOpen(UUID tenantId, String number, Instant now) {
        return jdbc.sql("SELECT count(*) FROM chat.wa_windows WHERE tenant_id = :t AND number = :n AND last_inbound_at > :s").param("t", tenantId).param("n", number)
                .param("s", Timestamp.from(now.minus(Duration.ofHours(24)))).query(Long.class).single() > 0;
    }

    /** {name} becomes the customer's first name, {shop} the shop's name, and every {masculine|feminine} pair the form that fits the customer. */
    static String personalize(String text, String name, String shop, Gender gender) {
        String first = name == null ? "" : name.trim().split("\\s+")[0];
        return gender.apply(text.replace("{name}", first).replace("{shop}", shop));
    }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, String segment, String message) { return broadcast(tenantId, actor, new Req(segment, message, null, "PAID", false, null, false)); }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, Req r) {
        requireKey(r.segment());
        String text = r.message() == null ? "" : r.message().trim();
        if (text.length() < 3 || text.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 3 to 1000 characters");
        String mode = r.mode() == null ? "NONE" : r.mode().toUpperCase();
        if (!Set.of("NONE", "FREE", "PAID").contains(mode)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown way of sending");
        boolean push = Boolean.TRUE.equals(r.push());
        if ("NONE".equals(mode) && !push) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose WhatsApp, an app notification, or both");
        String imageUrl = null;
        if (r.imageFileId() != null) {
            if (jdbc.sql("SELECT count(*) FROM core.files WHERE id = :f AND tenant_id = :t AND category = 'PRODUCT_IMAGE'").param("f", r.imageFileId()).param("t", tenantId).query(Long.class).single() == 0)
                throw BusinessException.badRequest("VALIDATION_ERROR", "The picture was not found");
            String host = urls.hosts(List.of(tenantId)).get(tenantId);
            imageUrl = urls.baseUrl(host) + "/api/bff/files/" + r.imageFileId() + "/content?variant=medium";
        }
        String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
        if ("FREE".equals(mode) && !evolution.connected(tenantId)) throw BusinessException.conflict("EVOLUTION_OFF", "Link your own WhatsApp number first");
        if ("PAID".equals(mode) && channels.whatsappChannel(tenantId) == null) throw BusinessException.badRequest("NO_WHATSAPP", "Connect your WhatsApp number first");
        long recent = jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND mode = :m AND created_at > now() - interval '24 hours'").param("t", tenantId).param("m", mode).query(Long.class).single();
        if ("FREE".equals(mode) && jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND mode = 'FREE' AND status = 'SENDING'").param("t", tenantId).query(Long.class).single() > 0)
            throw BusinessException.conflict("BROADCAST_RUNNING", "A free broadcast is still being sent. Wait until it finishes: messages go out one every 10 seconds to protect your number");
        if ("FREE".equals(mode) && recent >= 3) throw BusinessException.conflict("BROADCAST_LIMIT", "Three free broadcasts a day at most, to protect your WhatsApp number from being reported as spam");
        if ("PAID".equals(mode) && recent >= 5) throw BusinessException.conflict("BROADCAST_LIMIT", "Five paid broadcasts a day at most");

        var rows = jdbc.sql(SegmentSql.BASE + "SELECT phone, name, wa, uid, gender FROM s WHERE (:g = 'ALL' OR segment = :g) ORDER BY spent DESC LIMIT 20000").param("t", tenantId).param("g", r.segment()).query().listOfRows();
        Instant now = Instant.now();
        List<Map<String, Object>> queue = new ArrayList<>();
        int noConsent = 0, noWindow = 0;
        boolean onlyConsented = Boolean.TRUE.equals(r.onlyConsented());
        for (var row : rows) {
            if ("PAID".equals(mode)) {
                if (row.get("wa") == null) { noConsent++; continue; }
                if (!windowOpen(tenantId, digits((String) row.get("wa")), now)) { noWindow++; continue; }
                queue.add(Map.of("phone", digits((String) row.get("wa")), "name", String.valueOf(row.get("name") == null ? "" : row.get("name")), "gender", genderOf(row)));
            } else if ("FREE".equals(mode)) {
                if (onlyConsented && row.get("wa") == null) { noConsent++; continue; }
                String to = digits((String) (row.get("wa") != null ? row.get("wa") : row.get("phone")));
                if (to.length() < 8 || to.length() > 15) continue;
                queue.add(Map.of("phone", to, "name", String.valueOf(row.get("name") == null ? "" : row.get("name")), "gender", genderOf(row)));
            }
        }
        long cost = "PAID".equals(mode) ? (long) queue.size() * priceMinor : 0;
        if ("PAID".equals(mode)) {
            if (queue.isEmpty()) throw BusinessException.conflict("NOBODY_TO_SEND", "No customer agreed to WhatsApp contact and wrote to you in the last 24 hours. Use your own number (free) or an app notification instead");
            if (wallet.balance(tenantId) < cost) throw BusinessException.conflict("NO_CREDIT", "Not enough WhatsApp credit: this needs " + cost / 100 + " EGP");
        }
        if ("FREE".equals(mode) && queue.isEmpty()) throw BusinessException.conflict("NOBODY_TO_SEND", "No customer to send to");

        UUID id = jdbc.sql("""
                INSERT INTO commerce.broadcasts (tenant_id, segment, message, audience, sent_count, skipped_window, skipped_consent, created_by, mode, push, image_file_id, pending_count, status)
                VALUES (:t, :s, :m, :a, 0, :w, :c, :u, :mode, :push, :img, :p, :st) RETURNING id
                """).param("t", tenantId).param("s", r.segment()).param("m", text).param("a", rows.size()).param("w", noWindow).param("c", noConsent).param("u", actor).param("mode", mode)
                .param("push", push).param("img", r.imageFileId()).param("p", queue.size()).param("st", queue.isEmpty() ? "DONE" : "SENDING").query(UUID.class).single();
        for (var q : queue) jdbc.sql("INSERT INTO commerce.broadcast_recipients (broadcast_id, phone, name, gender) VALUES (:b, :p, :n, :g)").param("b", id).param("p", q.get("phone")).param("n", q.get("name")).param("g", q.get("gender")).update();

        int pushed = 0;
        if (push) {
            String title = r.pushTitle() == null || r.pushTitle().isBlank() ? shop : r.pushTitle().trim();
            for (var row : rows) {
                if (row.get("uid") == null) continue;
                UUID uid = (UUID) row.get("uid");
                if (!hasDevice(uid)) continue;
                notifications.push(uid, tenantId, title.length() > 80 ? title.substring(0, 80) : title, personalize(text, (String) row.get("name"), shop, Gender.of(genderOf(row))), "{\"type\":\"BROADCAST\"}");
                pushed++;
            }
            if (pushed > 0) notifications.dispatchSoon();
            jdbc.sql("UPDATE commerce.broadcasts SET push_sent = :p WHERE id = :i").param("p", pushed).param("i", id).update();
        }
        audit.record(actor, tenantId, "BROADCAST_SENT", "broadcast", id, "{\"segment\":\"" + r.segment() + "\",\"mode\":\"" + mode + "\"}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("audience", rows.size());
        out.put("queued", queue.size());
        out.put("pushSent", pushed);
        out.put("skippedNoWindow", noWindow);
        out.put("skippedNoConsent", noConsent);
        out.put("etaMinutes", "FREE".equals(mode) ? (long) Math.ceil(queue.size() * Math.max(gapSeconds, 1) / 60.0) : 0);
        out.put("costMinor", cost);
        return out;
    }

    private static String genderOf(Map<String, Object> row) {
        if (row.get("gender") != null) return (String) row.get("gender");
        String g = GenderService.guess((String) row.get("name"));
        return g == null ? "UNKNOWN" : g;
    }

    // ---- the sender -----------------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelay = 3_000, initialDelay = 30_000)
    public void scheduled() {
        if (!scheduler) return;
        try { runDue(); } catch (RuntimeException e) { log.warn("Broadcast pass failed", e); }
    }

    /** One pass: sends a few waiting messages of each running broadcast. Returns how many went out. Public for tests. */
    public synchronized int runDue() {
        int sent = 0;
        for (var b : jdbc.sql("SELECT id, tenant_id, message, mode, image_file_id, last_sent_at FROM commerce.broadcasts WHERE status = 'SENDING' ORDER BY created_at LIMIT 20").query().listOfRows()) {
            UUID id = (UUID) b.get("id"), tenantId = (UUID) b.get("tenant_id");
            String mode = (String) b.get("mode"), text = (String) b.get("message");
            String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
            String imageUrl = b.get("image_file_id") == null ? null : urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/api/bff/files/" + b.get("image_file_id") + "/content?variant=medium";
            boolean free = "FREE".equals(mode);
            // free messages go out one at a time with a gap between them (10 seconds by default), however long the list is: a number that sends in bursts gets banned
            if (free && b.get("last_sent_at") != null && ((Timestamp) b.get("last_sent_at")).toInstant().isAfter(Instant.now().minusSeconds(gapSeconds))) continue;
            int limit = free ? 1 : PAID_PER_PASS;
            var due = jdbc.sql("SELECT id, phone, name, gender FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING' ORDER BY id LIMIT :l").param("b", id).param("l", limit).query().listOfRows();
            for (var rcp : due) {
                String body = personalize(text, (String) rcp.get("name"), shop, Gender.of((String) rcp.get("gender"))), to = (String) rcp.get("phone");
                String result;
                long charged = 0;
                if ("FREE".equals(mode)) {
                    var d = evolution.deliver(tenantId, to, body, imageUrl);
                    if (d == EvolutionService.Delivery.DEFERRED) break;   // at the per-minute limit: the rest waits for the next pass
                    result = d == EvolutionService.Delivery.SENT ? "SENT" : "FAILED";
                    jdbc.sql("UPDATE commerce.broadcasts SET last_sent_at = now() WHERE id = :i").param("i", id).update();
                } else {
                    String ref = "bc-" + id + "-" + rcp.get("id");
                    if (!wallet.tryDebit(tenantId, priceMinor, ref, "WhatsApp broadcast")) result = "FAILED";
                    else {
                        result = "SENT";
                        charged = priceMinor;
                        try {
                            var ch = channels.whatsappChannel(tenantId);
                            if (ch == null) throw new MetaGraph.MetaException("No WhatsApp channel");
                            Map<String, Object> msg = imageUrl == null
                                    ? Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", body))
                                    : Map.of("messaging_product", "whatsapp", "to", to, "type", "image", "image", Map.of("link", imageUrl, "caption", body));
                            graph.post(ch.phoneId() + "/messages", ch.token(), msg);
                        } catch (MetaGraph.MetaException e) {
                            wallet.credit(tenantId, priceMinor, "REFUND", ref, "Message not delivered to Meta", null);   // never charge for a message that was not sent
                            result = "FAILED";
                            charged = 0;
                            log.warn("Broadcast message failed: {}", e.getMessage());
                        }
                    }
                }
                jdbc.sql("UPDATE commerce.broadcast_recipients SET status = :s, sent_at = CASE WHEN :s = 'SENT' THEN now() END WHERE id = :i").param("s", result).param("i", rcp.get("id")).update();
                jdbc.sql("UPDATE commerce.broadcasts SET sent_count = sent_count + :ok, failed_count = failed_count + :bad, pending_count = pending_count - 1, charged_minor = charged_minor + :c WHERE id = :i")
                        .param("ok", "SENT".equals(result) ? 1 : 0).param("bad", "FAILED".equals(result) ? 1 : 0).param("c", charged).param("i", id).update();
                if ("SENT".equals(result)) sent++;
            }
            jdbc.sql("UPDATE commerce.broadcasts SET status = 'DONE' WHERE id = :i AND NOT EXISTS (SELECT 1 FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status = 'PENDING')").param("i", id).update();
        }
        return sent;
    }

    public List<Map<String, Object>> history(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, segment, message, mode, push, push_sent, audience, sent_count, failed_count, pending_count, charged_minor, status, skipped_window, skipped_consent, created_at FROM commerce.broadcasts WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 30").param("t", tenantId).query().listOfRows());
    }
}
