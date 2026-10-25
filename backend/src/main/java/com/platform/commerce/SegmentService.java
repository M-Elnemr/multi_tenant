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

    public record Req(String segment, String message, UUID imageFileId, String mode, Boolean push, String pushTitle, Boolean onlyConsented, List<String> variants) {}

    private final JdbcClient jdbc;
    private final MetaChannelService channels;
    private final MetaGraph graph;
    private final AuditService audit;
    private final EvolutionService evolution;
    private final WalletService wallet;
    private final NotificationService notifications;
    private final com.platform.chat.AiProvider ai;
    private final com.platform.core.auth.LoginThrottle throttle;
    private final OpenUrls urls;
    private final long priceMinor;
    private final boolean scheduler;
    private final int gapMin, gapMax, dailyCap, quietFrom, quietTo;

    private final com.platform.chat.AiUsageService aiUsage;

    public SegmentService(JdbcClient jdbc, MetaChannelService channels, MetaGraph graph, AuditService audit, EvolutionService evolution, WalletService wallet, NotificationService notifications, OpenUrls urls, com.platform.chat.AiProvider ai, com.platform.core.auth.LoginThrottle throttle, com.platform.chat.AiUsageService aiUsage,
                          @Value("${app.whatsapp.broadcast-price-minor:300}") long priceMinor, @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler, @Value("${app.broadcast.free-gap-min-seconds:8}") int gapMin, @Value("${app.broadcast.free-gap-max-seconds:25}") int gapMax,
                          @Value("${app.broadcast.free-daily-cap:500}") int dailyCap, @Value("${app.broadcast.quiet-from-hour:22}") int quietFrom, @Value("${app.broadcast.quiet-to-hour:8}") int quietTo) {
        this.aiUsage = aiUsage;
        this.gapMin = Math.max(0, gapMin);
        this.gapMax = Math.max(this.gapMin, gapMax);
        this.dailyCap = Math.max(1, dailyCap);
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
        this.jdbc = jdbc;
        this.channels = channels;
        this.graph = graph;
        this.audit = audit;
        this.evolution = evolution;
        this.wallet = wallet;
        this.notifications = notifications;
        this.ai = ai;
        this.throttle = throttle;
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

    private static final java.util.regex.Pattern SPIN = java.util.regex.Pattern.compile("\\{\\{([^{}]{1,300})\\}\\}");

    /**
     * The text one customer receives. {{a|b|c}} picks one wording (the same pick every time for the same customer); {name}, {shop}, {last_product}, {last_order}, {last_date}
     * and {orders_count} are filled from the customer and their previous orders; every {masculine|feminine} pair takes the form that fits the customer.
     */
    static String personalize(String text, String name, String shop, Gender gender, Map<String, Object> vars, long seed) {
        var rnd = new java.util.Random(seed);
        var spin = SPIN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (spin.find()) {
            String[] options = spin.group(1).split("\\|");
            spin.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(options[rnd.nextInt(options.length)].trim()));
        }
        spin.appendTail(sb);
        String out = sb.toString();
        boolean arabic = out.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF);
        String first = name == null ? "" : name.trim().split("\\s+")[0];
        out = out.replace("{name}", first).replace("{shop}", shop);
        out = out.replace("{last_product}", str(vars, "lastProduct", arabic ? "طلبك السابق" : "your last order"))
                .replace("{last_order}", str(vars, "lastOrder", arabic ? "طلبك السابق" : "your last order"))
                .replace("{last_date}", str(vars, "lastDate", ""))
                .replace("{orders_count}", str(vars, "orders", ""));
        return gender.apply(out);
    }

    private static String str(Map<String, Object> vars, String key, String fallback) {
        Object v = vars == null ? null : vars.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /** Which of the wordings a customer gets: spread evenly and always the same for the same customer. */
    static String pickVersion(String main, List<String> variants, UUID recipient) {
        if (variants == null || variants.isEmpty()) return main;
        int i = Math.floorMod(recipient.hashCode(), variants.size() + 1);
        return i == 0 ? main : variants.get(i - 1);
    }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, String segment, String message) { return broadcast(tenantId, actor, new Req(segment, message, null, "PAID", false, null, false, null)); }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, Req r) {
        requireKey(r.segment());
        String text = r.message() == null ? "" : r.message().trim();
        if (text.length() < 3 || text.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 3 to 1000 characters");
        List<String> variants = new ArrayList<>();
        if (r.variants() != null) for (String v : r.variants()) { String t = v == null ? "" : v.trim(); if (!t.isEmpty()) variants.add(t); }
        if (variants.size() > 5) throw BusinessException.badRequest("VALIDATION_ERROR", "At most 5 other wordings");
        for (String v : variants) if (v.length() < 3 || v.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Each wording must be 3 to 1000 characters");
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

        var rows = jdbc.sql(SegmentSql.BASE + "SELECT phone, name, wa, uid, gender, opted_out, orders FROM s WHERE (:g = 'ALL' OR segment = :g) ORDER BY spent DESC LIMIT 20000").param("t", tenantId).param("g", r.segment()).query().listOfRows();
        Instant now = Instant.now();
        List<Map<String, Object>> queue = new ArrayList<>();
        int noConsent = 0, noWindow = 0;
        boolean onlyConsented = Boolean.TRUE.equals(r.onlyConsented());
        int optedOut = 0;
        var prev = previousOrders(tenantId);
        for (var row : rows) {
            if (Boolean.TRUE.equals(row.get("opted_out")) && !"NONE".equals(mode)) { optedOut++; continue; }   // they asked us to stop
            if ("PAID".equals(mode)) {
                if (row.get("wa") == null) { noConsent++; continue; }
                if (!windowOpen(tenantId, digits((String) row.get("wa")), now)) { noWindow++; continue; }
                queue.add(Map.of("phone", digits((String) row.get("wa")), "name", String.valueOf(row.get("name") == null ? "" : row.get("name")), "gender", genderOf(row), "vars", varsJson(row, prev)));
            } else if ("FREE".equals(mode)) {
                if (onlyConsented && row.get("wa") == null) { noConsent++; continue; }
                String to = digits((String) (row.get("wa") != null ? row.get("wa") : row.get("phone")));
                if (to.length() < 8 || to.length() > 15) continue;
                queue.add(Map.of("phone", to, "name", String.valueOf(row.get("name") == null ? "" : row.get("name")), "gender", genderOf(row), "vars", varsJson(row, prev)));
            }
        }
        long cost = "PAID".equals(mode) ? (long) queue.size() * priceMinor : 0;
        if ("PAID".equals(mode)) {
            if (queue.isEmpty()) throw BusinessException.conflict("NOBODY_TO_SEND", "No customer agreed to WhatsApp contact and wrote to you in the last 24 hours. Use your own number (free) or an app notification instead");
            if (wallet.balance(tenantId) < cost) throw BusinessException.conflict("NO_CREDIT", "Not enough WhatsApp credit: this needs " + cost / 100 + " EGP");
        }
        if ("FREE".equals(mode) && queue.isEmpty()) throw BusinessException.conflict("NOBODY_TO_SEND", "No customer to send to");

        UUID id = jdbc.sql("""
                INSERT INTO commerce.broadcasts (tenant_id, segment, message, audience, sent_count, skipped_window, skipped_consent, created_by, mode, push, image_file_id, pending_count, status, variants)
                VALUES (:t, :s, :m, :a, 0, :w, :c, :u, :mode, :push, :img, :p, :st, CAST(:v AS jsonb)) RETURNING id
                """).param("t", tenantId).param("s", r.segment()).param("m", text).param("a", rows.size()).param("w", noWindow).param("c", noConsent).param("u", actor).param("mode", mode)
                .param("push", push).param("img", r.imageFileId()).param("p", queue.size()).param("st", queue.isEmpty() ? "DONE" : "SENDING").param("v", variants.isEmpty() ? null : toJson(variants)).query(UUID.class).single();
        for (var q : queue) jdbc.sql("INSERT INTO commerce.broadcast_recipients (broadcast_id, phone, name, gender, vars) VALUES (:b, :p, :n, :g, CAST(:v AS jsonb))").param("b", id).param("p", q.get("phone")).param("n", q.get("name")).param("g", q.get("gender")).param("v", q.get("vars")).update();

        int pushed = 0;
        if (push) {
            String title = r.pushTitle() == null || r.pushTitle().isBlank() ? shop : r.pushTitle().trim();
            for (var row : rows) {
                if (row.get("uid") == null) continue;
                UUID uid = (UUID) row.get("uid");
                if (!hasDevice(uid)) continue;
                notifications.push(uid, tenantId, title.length() > 80 ? title.substring(0, 80) : title, personalize(pickVersion(text, variants, uid), (String) row.get("name"), shop, Gender.of(genderOf(row)), varsOf(row, prev), uid.getMostSignificantBits()), "{\"type\":\"BROADCAST\"}");
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
        out.put("etaMinutes", "FREE".equals(mode) ? (long) Math.ceil(queue.size() * ((gapMin + gapMax) / 2.0 + 1) / 60.0) : 0);
        out.put("etaDays", "FREE".equals(mode) ? (long) Math.ceil(queue.size() / (double) dailyCap) : 0);
        out.put("skippedOptOut", optedOut);
        out.put("costMinor", cost);
        return out;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String toJson(Object o) { try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }

    /** What each customer last bought, by phone: the order number, its date and the first product in it. */
    private Map<String, Map<String, Object>> previousOrders(UUID tenantId) {
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        for (var r : jdbc.sql("""
                SELECT DISTINCT ON (o.customer_phone_snapshot) o.customer_phone_snapshot AS phone, o.order_number, o.created_at,
                       (SELECT i.product_name_snapshot FROM commerce.order_items i WHERE i.order_id = o.id ORDER BY i.created_at, i.id LIMIT 1) AS product
                FROM commerce.orders o WHERE o.tenant_id = :t AND o.status NOT IN ('CANCELLED', 'PENDING') ORDER BY o.customer_phone_snapshot, o.created_at DESC
                """).param("t", tenantId).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lastOrder", r.get("order_number"));
            m.put("lastProduct", r.get("product"));
            m.put("lastDate", java.time.LocalDate.ofInstant(((Timestamp) r.get("created_at")).toInstant(), java.time.ZoneId.of("Africa/Cairo")).format(java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy")));
            out.put((String) r.get("phone"), m);
        }
        return out;
    }

    private static Map<String, Object> varsOf(Map<String, Object> row, Map<String, Map<String, Object>> prev) {
        Map<String, Object> m = new LinkedHashMap<>(prev.getOrDefault((String) row.get("phone"), Map.of()));
        if (row.get("orders") != null) m.put("orders", String.valueOf(row.get("orders")));
        return m;
    }

    private static String varsJson(Map<String, Object> row, Map<String, Map<String, Object>> prev) { return toJson(varsOf(row, prev)); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVars(Object o) {
        try { return o == null ? Map.of() : JSON.readValue(o.toString(), Map.class); } catch (Exception e) { return Map.of(); }
    }

    /**
     * Rewrites a message in a few different ways (same offer, same links, same placeholders) so that not every customer gets the identical text.
     * The owner reads and edits the suggestions before sending anything. Nothing new is promised: prices, offers and links stay as written.
     */
    public List<String> suggestVariants(UUID tenantId, String message, int count) {
        String text = message == null ? "" : message.trim();
        if (text.length() < 3 || text.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 3 to 1000 characters");
        int n = Math.max(1, Math.min(count, 4));
        throttle.check("bc-variants|" + tenantId, 10, Duration.ofHours(1));
        String system = "You rewrite a WhatsApp message that a shop sends to its customers. Write exactly " + n + " different versions: same meaning, same offer, same prices, same links and same emojis' mood, "
                + "but with different wording and sentence structure, natural and friendly, in the same language and dialect as the original. Keep every placeholder in curly braces exactly as written "
                + "(for example {name}, {shop}, {last_product}, {last_order}, {last_date}, {orders_count}) and every pair such as {بيك|بيكي}. Do not add new offers, discounts, claims or links. "
                + "Output only the versions, separated by a line that contains only ---.";
        String out;
        try { var turn = ai.next(system, List.of(com.platform.chat.AiProvider.Turn.user(text)), List.of()); aiUsage.recordCall(tenantId, "BROADCAST_VARIANTS", turn); out = turn.text(); }
        catch (com.platform.chat.AiProvider.AiUnavailableException e) { throw BusinessException.conflict("AI_NOT_AVAILABLE", "The assistant is not available right now"); }
        java.util.regex.Pattern ph = java.util.regex.Pattern.compile("\\{[^{}]{1,60}\\}");
        List<String> need = new ArrayList<>();
        var m = ph.matcher(text);
        while (m.find()) need.add(m.group());
        List<String> versions = new ArrayList<>();
        for (String part : (out == null ? "" : out).split("\\n\\s*---\\s*\\n?")) {
            String v = part.trim();
            if (v.length() < 3 || v.length() > 1000 || v.equals(text) || versions.contains(v)) continue;
            boolean keeps = true;
            for (String t : need) if (!v.contains(t)) { keeps = false; break; }
            if (keeps) versions.add(v);
            if (versions.size() >= n) break;
        }
        return versions;
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
        for (var b : jdbc.sql("SELECT id, tenant_id, message, mode, image_file_id, next_send_at, variants::text AS variants FROM commerce.broadcasts WHERE status = 'SENDING' AND " + com.platform.shared.TenantState.running("tenant_id") + " ORDER BY created_at LIMIT 20").query().listOfRows()) {
            UUID id = (UUID) b.get("id"), tenantId = (UUID) b.get("tenant_id");
            String mode = (String) b.get("mode"), text = (String) b.get("message");
            List<String> versions = new ArrayList<>();
            try { if (b.get("variants") != null) for (var v : JSON.readTree(b.get("variants").toString())) versions.add(v.asText()); } catch (Exception ignored) { /* no extra wordings */ }
            String shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single();
            String imageUrl = b.get("image_file_id") == null ? null : urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/api/bff/files/" + b.get("image_file_id") + "/content?variant=medium";
            boolean free = "FREE".equals(mode);
            if (free) {
                // 1) too many customers asked to stop: pause and let the owner look
                var stats = jdbc.sql("SELECT count(*) FILTER (WHERE opted_out_at IS NOT NULL) AS stops, count(*) FILTER (WHERE status = 'SENT') AS sent FROM commerce.broadcast_recipients WHERE broadcast_id = :b").param("b", id).query().listOfRows().get(0);
                long stops = ((Number) stats.get("stops")).longValue(), sentSoFar = ((Number) stats.get("sent")).longValue();
                if (stops >= 3 && stops * 100 >= sentSoFar * 3) { jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'OPT_OUTS' WHERE id = :i").param("i", id).update(); continue; }
                // 2) quiet hours in the shop's own time: nobody gets a promotion at night
                if (quiet(tenantId)) { setReason(id, "QUIET_HOURS"); continue; }
                // 3) the daily limit per number
                long today = jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients r JOIN commerce.broadcasts x ON x.id = r.broadcast_id WHERE x.tenant_id = :t AND x.mode = 'FREE' AND r.status = 'SENT' AND r.sent_at > now() - interval '24 hours'").param("t", tenantId).query(Long.class).single();
                if (today >= dailyCap) { setReason(id, "DAILY_CAP"); continue; }
                // 4) a random gap after every message, however long the list is: a fixed rhythm looks like a bot, and bursts get numbers banned
                if (b.get("next_send_at") != null && ((Timestamp) b.get("next_send_at")).toInstant().isAfter(Instant.now())) continue;
                setReason(id, null);
            }
            int limit = free ? 1 : PAID_PER_PASS;
            var due = jdbc.sql("SELECT id, phone, name, gender, vars::text AS vars FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING' ORDER BY id LIMIT :l").param("b", id).param("l", limit).query().listOfRows();
            for (var rcp : due) {
                String body = personalize(pickVersion(text, versions, (UUID) rcp.get("id")), (String) rcp.get("name"), shop, Gender.of((String) rcp.get("gender")), parseVars(rcp.get("vars")), ((UUID) rcp.get("id")).getMostSignificantBits()), to = (String) rcp.get("phone");
                String result;
                long charged = 0;
                if ("FREE".equals(mode)) {
                    var d = evolution.deliver(tenantId, to, body + OPT_OUT_LINE, imageUrl);   // every free message tells the customer how to stop, so they reply instead of reporting
                    if (d == EvolutionService.Delivery.DEFERRED) break;   // at the per-minute limit: the rest waits for the next pass
                    result = d == EvolutionService.Delivery.SENT ? "SENT" : "FAILED";
                    int gap = gapMax <= gapMin ? gapMin : gapMin + java.util.concurrent.ThreadLocalRandom.current().nextInt(gapMax - gapMin + 1);
                    jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = now() + make_interval(secs => :g) WHERE id = :i").param("g", gap).param("i", id).update();
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
                if (free) {
                    // five failures in a row means something is wrong with the number: stop instead of hammering it
                    int streak = jdbc.sql("UPDATE commerce.broadcasts SET consecutive_failures = CASE WHEN :ok THEN 0 ELSE consecutive_failures + 1 END WHERE id = :i RETURNING consecutive_failures").param("ok", "SENT".equals(result)).param("i", id).query(Integer.class).single();
                    if (streak >= 5) { jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'FAILURES' WHERE id = :i").param("i", id).update(); break; }
                }
            }
            jdbc.sql("UPDATE commerce.broadcasts SET status = 'DONE' WHERE id = :i AND NOT EXISTS (SELECT 1 FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status = 'PENDING')").param("i", id).update();
        }
        return sent;
    }

    static final String OPT_OUT_LINE = "\n\nلإيقاف الرسائل اكتب: إيقاف · Reply STOP to unsubscribe";

    private void setReason(UUID id, String reason) { jdbc.sql("UPDATE commerce.broadcasts SET pause_reason = CAST(:r AS varchar) WHERE id = :i AND pause_reason IS DISTINCT FROM CAST(:r AS varchar)").param("r", reason).param("i", id).update(); }

    /** True during the shop's quiet hours (22:00 to 08:00 by default, in the shop's time zone). From and to being equal switches quiet hours off. */
    private boolean quiet(UUID tenantId) {
        if (quietFrom == quietTo) return false;
        int hour;
        try { hour = java.time.ZonedDateTime.now(java.time.ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single())).getHour(); }
        catch (RuntimeException e) { hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).getHour(); }
        return quietFrom > quietTo ? (hour >= quietFrom || hour < quietTo) : (hour >= quietFrom && hour < quietTo);
    }

    /** The owner looked at a paused broadcast and wants it to go on. */
    public Map<String, Object> resume(UUID tenantId, UUID id) {
        if (jdbc.sql("UPDATE commerce.broadcasts SET status = 'SENDING', pause_reason = NULL, consecutive_failures = 0, next_send_at = NULL WHERE id = :i AND tenant_id = :t AND status = 'PAUSED'").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.conflict("INVALID_STATUS", "This broadcast is not paused");
        return Map.of("ok", true);
    }

    /** Stops a broadcast for good: nothing more is sent from it. */
    public Map<String, Object> cancel(UUID tenantId, UUID id) {
        if (jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED', pause_reason = NULL, pending_count = 0 WHERE id = :i AND tenant_id = :t AND status IN ('SENDING', 'PAUSED')").param("i", id).param("t", tenantId).update() == 0)
            throw BusinessException.conflict("INVALID_STATUS", "This broadcast is already finished");
        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'STOPPED' WHERE broadcast_id = :i AND status = 'PENDING'").param("i", id).update();
        return Map.of("ok", true);
    }

    public List<Map<String, Object>> history(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, segment, message, mode, push, push_sent, audience, sent_count, failed_count, pending_count, charged_minor, status, pause_reason, next_send_at, skipped_window, skipped_consent, created_at FROM commerce.broadcasts WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 30").param("t", tenantId).query().listOfRows());
    }
}
