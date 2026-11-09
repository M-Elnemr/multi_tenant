package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.OutboundBilling;
import com.platform.billing.WalletService;
import com.platform.chat.EvolutionService;
import com.platform.chat.MetaBroadcastSender;
import com.platform.chat.unofficial.HumanPolicies;
import com.platform.chat.unofficial.HumanPolicy;
import com.platform.chat.unofficial.UnofficialMetaService;
import com.platform.notifications.NotificationService;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.Gender;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Customer segments computed from orders (nothing to maintain) and broadcasts to them. An audience is "anyone in ANY chosen segment on ANY chosen platform":
 * WhatsApp (the order customers' phones, sent from the shop's own linked number), Messenger and Instagram (the people who wrote to the shop, reachable only while the platform's
 * window is open). Every message costs the same small price, charged just before it is sent and given back if the send fails; when the credit runs out the broadcast pauses until the
 * shop tops up. Messages are queued and sent by a job, one every 5 to 15 seconds, so a long list never blocks the screen and a number never sends in a burst.
 */
@Service
public class SegmentService {
    private static final Logger log = LoggerFactory.getLogger(SegmentService.class);
    public static final List<String> SEGMENT_ORDER = List.of("ALL", "NEW", "REPEAT", "VIP", "AT_RISK", "LAPSED");
    public static final Set<String> KEYS = Set.copyOf(SEGMENT_ORDER);
    public static final List<String> PLATFORMS = List.of("WHATSAPP", "FACEBOOK", "INSTAGRAM");

    public record Req(List<String> segments, List<String> platforms, String message, UUID imageFileId, Boolean push, String pushTitle, List<String> variants, List<String> manualPlatforms) {}

    /** One person on one platform. {@code key} is the phone digits (WhatsApp) or the conversation id (Messenger, Instagram). */
    record Aud(String key, String name, String phone, String platform, String segment, long orders, long spentMinor, Instant lastOrderAt, String externalId, boolean reachable, String reason,
               String gender, String orderPhone, Integer orderCount, String route, boolean manualOk) {}

    private final JdbcClient jdbc;
    private final MetaBroadcastSender meta;
    private final AuditService audit;
    private final EvolutionService evolution;
    private final WalletService wallet;
    private final OutboundBilling billing;
    private final NotificationService notifications;
    private final com.platform.chat.AiProvider ai;
    private final com.platform.core.auth.LoginThrottle throttle;
    private final OpenUrls urls;
    private final boolean scheduler;
    private final int gapMin, gapMax, quietFrom, quietTo;
    private final UnofficialMetaService unofficial;
    private final HumanPolicies policies;
    private final com.platform.chat.EvolutionApi evolutionApi;
    private final java.util.Random rnd = new java.util.Random();

    private final com.platform.chat.AiUsageService aiUsage;

    public SegmentService(JdbcClient jdbc, MetaBroadcastSender meta, AuditService audit, EvolutionService evolution, WalletService wallet, OutboundBilling billing, NotificationService notifications, OpenUrls urls, com.platform.chat.AiProvider ai, com.platform.core.auth.LoginThrottle throttle, com.platform.chat.AiUsageService aiUsage,
                          UnofficialMetaService unofficial, HumanPolicies policies, com.platform.chat.EvolutionApi evolutionApi,
                          @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler, @Value("${app.broadcast.free-gap-min-seconds:5}") int gapMin, @Value("${app.broadcast.free-gap-max-seconds:15}") int gapMax,
                          @Value("${app.broadcast.quiet-from-hour:22}") int quietFrom, @Value("${app.broadcast.quiet-to-hour:8}") int quietTo) {
        this.aiUsage = aiUsage;
        this.unofficial = unofficial;
        this.policies = policies;
        this.evolutionApi = evolutionApi;
        this.gapMin = Math.max(0, gapMin);
        this.gapMax = Math.max(this.gapMin, gapMax);
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
        this.jdbc = jdbc;
        this.meta = meta;
        this.audit = audit;
        this.evolution = evolution;
        this.wallet = wallet;
        this.billing = billing;
        this.notifications = notifications;
        this.ai = ai;
        this.throttle = throttle;
        this.urls = urls;
        this.scheduler = scheduler;
    }

    // ---- who is in a segment -------------------------------------------------------------------------------------------------

    /** Parses a comma separated list ("VIP,NEW"); null or empty means everything. Unknown names are a 400. */
    public static Set<String> parseSet(String csv, List<String> allowed, String what) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) for (String p : csv.split(",")) { String v = p.trim().toUpperCase(); if (!v.isEmpty()) out.add(v); }
        return checkSet(out, allowed, what);
    }

    static Set<String> checkSet(java.util.Collection<String> given, List<String> allowed, String what) {
        Set<String> out = new LinkedHashSet<>();
        if (given != null) for (String v : given) { String u = v == null ? "" : v.trim().toUpperCase(); if (!u.isEmpty()) out.add(u); }
        for (String v : out) if (!allowed.contains(v)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown " + what);
        return out;
    }

    private static boolean segmentWanted(Set<String> segs, String segment) { return segs.isEmpty() || segs.contains("ALL") || segs.contains(segment); }

    static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }

    private static Instant instant(Object ts) { return ts instanceof Timestamp t ? t.toInstant() : null; }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }

    /**
     * Everybody in any of the segments on any platform (a person on two platforms is two entries). WhatsApp comes from the order customers' phones; Messenger and Instagram from the chats,
     * linked to a customer by the phone they gave or their account, otherwise NEW (REPEAT when the chat is older than 7 days with more than 2 customer messages).
     * The only protection kept silently: someone who asked to stop is never reachable.
     */
    List<Aud> audience(UUID tenantId, Set<String> segs) { return audience(tenantId, segs, Set.of()); }

    /** Why a contact whose window is closed must not get a manual-style message (null = fine). Same limits at creation and again at the moment of sending. */
    static String manualGuard(HumanPolicy.Profile pr, Instant lastCustomer, long totalMsgs, long customerMsgs, boolean recentlyContacted, Instant now) {
        if (pr.maxContactAgeDays() > 0 && (lastCustomer == null || lastCustomer.isBefore(now.minus(Duration.ofDays(pr.maxContactAgeDays()))))) return "MANUAL_TOO_OLD";
        if (pr.minMessages() > 0 && (customerMsgs < 1 || totalMsgs < pr.minMessages())) return "MANUAL_FEW_MESSAGES";
        if (recentlyContacted) return "MANUAL_RECENT";
        return null;
    }

    /** {@code manualPlats}: platforms where the owner chose the manual-style route for contacts whose window is closed (and where that route is really available). */
    List<Aud> audience(UUID tenantId, Set<String> segs, Set<String> manualPlats) {
        List<Aud> out = new ArrayList<>();
        boolean own = evolution.connected(tenantId);
        for (var r : jdbc.sql(SegmentSql.BASE + "SELECT phone, name, orders, spent, last_order, segment, gender, opted_out FROM s ORDER BY spent DESC LIMIT 20000").param("t", tenantId).query().listOfRows()) {
            String seg = (String) r.get("segment");
            if (!segmentWanted(segs, seg)) continue;
            String phone = (String) r.get("phone"), d = digits(phone);
            String reason = d.length() < 8 || d.length() > 15 ? "NO_PHONE" : Boolean.TRUE.equals(r.get("opted_out")) ? "OPTED_OUT" : !own ? "NO_OWN_NUMBER" : null;
            out.add(new Aud(d, (String) r.get("name"), phone, "WHATSAPP", seg, num(r.get("orders")), num(r.get("spent")), instant(r.get("last_order")), null, reason == null, reason, (String) r.get("gender"), phone, null, "OFFICIAL", false));
        }
        Instant now = Instant.now();
        var pr = policies.manual().profile();
        int sameDays = Math.max(1, pr.sameContactDays());
        for (var r : jdbc.sql(SegmentSql.BASE + """
                SELECT cv.id AS conv_id, cv.channel, cv.external_id, cv.display_name, cv.contact_phone, cv.created_at, cv.last_customer_at, cv.window_until, cv.broadcast_opt_out_at IS NOT NULL AS stopped,
                       (SELECT count(*) FROM (SELECT 1 FROM chat.messages m WHERE m.conversation_id = cv.id AND m.role = 'user' LIMIT 3) x) AS cust_msgs,
                       (SELECT count(*) FROM (SELECT 1 FROM chat.messages m WHERE m.conversation_id = cv.id LIMIT 3) x) AS all_msgs,
                       EXISTS (SELECT 1 FROM commerce.broadcast_recipients br JOIN commerce.broadcasts bb ON bb.id = br.broadcast_id
                                WHERE bb.tenant_id = :t AND br.route = 'MANUAL_STYLE' AND br.channel = cv.channel AND br.external_id = cv.external_id AND br.status = 'SENT'
                                  AND br.sent_at > now() - make_interval(days => :sameDays)) AS recent_manual,
                       l.phone AS o_phone, l.name AS o_name, l.orders, l.spent, l.last_order, l.segment, l.opted_out, l.gender
                FROM chat.conversations cv
                LEFT JOIN LATERAL (SELECT s.* FROM s WHERE (length(regexp_replace(coalesce(cv.contact_phone, ''), '[^0-9]', '', 'g')) >= 8
                                                          AND right(regexp_replace(s.phone, '[^0-9]', '', 'g'), 9) = right(regexp_replace(cv.contact_phone, '[^0-9]', '', 'g'), 9))
                                                         OR (cv.client_id IS NOT NULL AND s.uid = cv.client_id) LIMIT 1) l ON TRUE
                WHERE cv.tenant_id = :t AND cv.channel IN ('FACEBOOK', 'INSTAGRAM')
                ORDER BY coalesce(cv.last_customer_at, cv.created_at) DESC LIMIT 20000
                """).param("t", tenantId).param("sameDays", sameDays).query().listOfRows()) {
            String linked = (String) r.get("segment");
            Instant created = instant(r.get("created_at"));
            String seg = linked != null ? linked : (created != null && created.isBefore(now.minus(Duration.ofDays(7))) && num(r.get("cust_msgs")) > 2 ? "REPEAT" : "NEW");
            if (!segmentWanted(segs, seg)) continue;
            Instant lastCustomer = instant(r.get("last_customer_at")), until = instant(r.get("window_until"));
            boolean open = (lastCustomer != null && lastCustomer.isAfter(now.minus(Duration.ofHours(23)))) || (until != null && until.isAfter(now));
            boolean stopped = Boolean.TRUE.equals(r.get("stopped")) || Boolean.TRUE.equals(r.get("opted_out"));
            String platform = (String) r.get("channel");
            String reason = stopped ? "OPTED_OUT" : !open ? "OUTSIDE_WINDOW" : null;
            String route = "OFFICIAL";
            boolean manualOk = false;
            if (!stopped && !open) {   // closed window: only the manual-style route (unofficial, owner's choice) can still reach this person, and only inside its audience limits
                String g = manualGuard(pr, lastCustomer, num(r.get("all_msgs")), num(r.get("cust_msgs")), Boolean.TRUE.equals(r.get("recent_manual")), now);
                manualOk = g == null;
                if (manualPlats.contains(platform)) { route = "MANUAL_STYLE"; reason = g; }
            }
            String name = r.get("o_name") != null ? (String) r.get("o_name") : (String) r.get("display_name");
            String phone = r.get("contact_phone") != null ? (String) r.get("contact_phone") : (String) r.get("o_phone");
            out.add(new Aud(String.valueOf(r.get("conv_id")), name, phone, platform, seg, num(r.get("orders")), num(r.get("spent")), instant(r.get("last_order")), (String) r.get("external_id"), reason == null, reason,
                    (String) r.get("gender"), (String) r.get("o_phone"), null, route, manualOk));
        }
        return out;
    }

    private Map<String, Object> byPlatform(UUID tenantId, List<Aud> all) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String p : PLATFORMS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", all.stream().filter(a -> a.platform().equals(p)).count());
            m.put("reachable", all.stream().filter(a -> a.platform().equals(p) && a.reachable()).count());
            // people outside the 24 h window the manual-style route could reach (only counted when the shop has an ACTIVE session and the feature is on)
            m.put("manualStyleReachable", "WHATSAPP".equals(p) || !unofficial.reachable(tenantId, p) ? 0L : all.stream().filter(a -> a.platform().equals(p) && a.manualOk()).count());
            out.put(p, m);
        }
        return out;
    }

    /** What the manual-style route looks like per platform: available now, the account, the warm-up day and today's cap. */
    private Map<String, Object> manualInfo(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") Map<String, Object> pl = (Map<String, Object>) unofficial.overview(tenantId).get("platforms");
        for (String p : List.of("FACEBOOK", "INSTAGRAM")) {
            @SuppressWarnings("unchecked") Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) pl.get(p));
            m.put("available", unofficial.reachable(tenantId, p));
            out.put(p, m);
        }
        return out;
    }

    /** The segments screen: how many people each segment has on the chosen platforms, and what each platform could reach. */
    public Map<String, Object> overview(UUID tenantId, Set<String> platforms) {
        List<Aud> all = audience(tenantId, Set.of("ALL"));
        List<Aud> sel = all.stream().filter(a -> platforms.isEmpty() || platforms.contains(a.platform())).toList();
        List<Map<String, Object>> segs = new ArrayList<>();
        for (String k : SEGMENT_ORDER) segs.add(Map.of("key", k, "count", "ALL".equals(k) ? sel.size() : sel.stream().filter(a -> a.segment().equals(k)).count()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segments", segs);
        out.put("total", sel.size());
        out.put("byPlatform", byPlatform(tenantId, all));
        out.put("manualStyle", manualInfo(tenantId));
        out.put("ownNumberLinked", evolution.connected(tenantId));
        out.put("balanceMinor", wallet.balance(tenantId));
        out.put("messagePriceMinor", billing.priceMinor());
        return out;
    }

    /** The people behind the numbers. Totals ({@code reachable}, {@code outsideWindow}, {@code byPlatform}) cover the whole chosen audience; {@code total} and {@code data} follow the search too. */
    public Map<String, Object> customers(UUID tenantId, Set<String> segs, Set<String> platforms, String q, int limit, int offset, Set<String> manual) {
        List<Aud> all = audience(tenantId, segs, usable(tenantId, manual));
        List<Aud> sel = all.stream().filter(a -> platforms.isEmpty() || platforms.contains(a.platform())).toList();
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<Aud> shown = needle.isEmpty() ? sel : sel.stream().filter(a -> (a.name() != null && a.name().toLowerCase().contains(needle)) || (a.phone() != null && digits(a.phone()).contains(digits(needle)) && !digits(needle).isEmpty())
                || (a.phone() != null && a.phone().contains(needle))).toList();
        int from = Math.max(0, Math.min(offset, shown.size())), to = Math.min(shown.size(), from + Math.max(1, Math.min(limit, 200)));
        List<Map<String, Object>> data = new ArrayList<>();
        for (Aud a : shown.subList(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.key());
            m.put("name", a.name());
            m.put("phone", a.phone());
            m.put("platform", a.platform());
            m.put("segment", a.segment());
            m.put("orders", a.orders());
            m.put("spentMinor", a.spentMinor());
            m.put("lastOrderAt", a.lastOrderAt());
            m.put("reachable", a.reachable());
            m.put("reason", a.reason());
            m.put("route", a.route());
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", shown.size());
        out.put("reachable", sel.stream().filter(Aud::reachable).count());
        out.put("outsideWindow", sel.stream().filter(a -> "OUTSIDE_WINDOW".equals(a.reason())).count());
        out.put("manualStyle", sel.stream().filter(a -> "MANUAL_STYLE".equals(a.route()) && a.reachable()).count());
        out.put("byPlatform", byPlatform(tenantId, all));
        return out;
    }

    /** The platforms (of those asked for) where the manual-style route is really available: feature on, real transport, ACTIVE session. */
    private Set<String> usable(UUID tenantId, Set<String> asked) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : asked) if (!"WHATSAPP".equals(p) && unofficial.reachable(tenantId, p)) out.add(p);
        return out;
    }

    private boolean hasDevice(UUID user) { return jdbc.sql("SELECT EXISTS (SELECT 1 FROM notifications.device_tokens WHERE principal_id = :u)").param("u", user).query(Boolean.class).single(); }

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

    // ---- creating a broadcast ------------------------------------------------------------------------------------------------

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, Req r) {
        Set<String> segs = checkSet(r.segments(), SEGMENT_ORDER, "segment");
        Set<String> plats = checkSet(r.platforms(), PLATFORMS, "platform");
        if (segs.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose at least one segment");
        if (plats.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose at least one platform");
        String text = r.message() == null ? "" : r.message().trim();
        if (text.length() < 3 || text.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 3 to 1000 characters");
        List<String> variants = new ArrayList<>();
        if (r.variants() != null) for (String v : r.variants()) { String t = v == null ? "" : v.trim(); if (!t.isEmpty()) variants.add(t); }
        if (variants.size() > 5) throw BusinessException.badRequest("VALIDATION_ERROR", "At most 5 other wordings");
        for (String v : variants) if (v.length() < 3 || v.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Each wording must be 3 to 1000 characters");
        boolean push = Boolean.TRUE.equals(r.push());
        if (r.imageFileId() != null && jdbc.sql("SELECT count(*) FROM core.files WHERE id = :f AND tenant_id = :t AND category = 'PRODUCT_IMAGE'").param("f", r.imageFileId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "The picture was not found");
        String shop = shopName(tenantId);
        if (plats.contains("WHATSAPP") && !evolution.connected(tenantId)) throw BusinessException.conflict("NO_OWN_NUMBER", "Link your own WhatsApp number first");
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND status = 'SENDING'").param("t", tenantId).query(Long.class).single() > 0)
            throw BusinessException.conflict("BROADCAST_RUNNING", "A broadcast is still being sent. Wait until it finishes: messages go out one every 5 to 15 seconds to protect your accounts");
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND created_at > now() - interval '24 hours'").param("t", tenantId).query(Long.class).single() >= 3)
            throw BusinessException.conflict("BROADCAST_LIMIT", "Three broadcasts a day at most, to protect your accounts from being reported as spam");

        // the manual-style route (unofficial, owner's explicit choice per platform) for people whose window is closed
        Set<String> manualAsked = checkSet(r.manualPlatforms(), List.of("FACEBOOK", "INSTAGRAM"), "platform");
        manualAsked.retainAll(plats);
        for (String p : manualAsked) if (!unofficial.reachable(tenantId, p)) throw BusinessException.conflict("MANUAL_STYLE_UNAVAILABLE", "Manual-style sending is not available on this platform right now");
        List<Aud> all = audience(tenantId, segs, manualAsked).stream().filter(a -> plats.contains(a.platform())).toList();
        List<Aud> queue = all.stream().filter(Aud::reachable).toList();
        long outside = all.stream().filter(a -> "OUTSIDE_WINDOW".equals(a.reason())).count();
        long skipped = all.size() - queue.size() - outside;
        long cost = queue.size() * billing.priceMinor();
        // content rules for every wording that will go out (one link, length, spam patterns), by the policy of the route it travels
        boolean anyWa = queue.stream().anyMatch(a -> "WHATSAPP".equals(a.platform())), anyManual = queue.stream().anyMatch(a -> "MANUAL_STYLE".equals(a.route()));
        List<String> raw = new ArrayList<>(variants);
        raw.add(0, text);
        for (String v : raw) {
            if (anyWa) { String why = policies.whatsapp().contentProblem(HumanPolicy.stripRepeatedLinks(v)); if (why != null) throw BusinessException.badRequest("CONTENT_REJECTED", "This message cannot be sent: " + why); }
            if (anyManual) { String why = policies.manual().contentProblem(HumanPolicy.stripRepeatedLinks(v) + OPT_OUT_LINE); if (why != null) throw BusinessException.badRequest("CONTENT_REJECTED", "This message cannot be sent by hand-style sending: " + why); }
        }
        if (anyManual && !policies.manual().enoughVariants(text, variants)) throw BusinessException.badRequest("VARIANTS_REQUIRED", "Hand-style sending needs at least " + policies.manual().profile().minVariants() + " different wordings of the message");
        for (String p : manualAsked) {   // one hand-style broadcast a day per platform
            if (queue.stream().noneMatch(a -> "MANUAL_STYLE".equals(a.route()) && a.platform().equals(p))) continue;
            if (jdbc.sql("""
                    SELECT count(*) FROM commerce.broadcasts b WHERE b.tenant_id = :t AND b.created_at > now() - interval '24 hours'
                       AND EXISTS (SELECT 1 FROM commerce.broadcast_recipients br WHERE br.broadcast_id = b.id AND br.route = 'MANUAL_STYLE' AND br.channel = :p AND (br.status = 'SENT' OR b.status IN ('SENDING', 'PAUSED', 'DONE')))
                    """).param("t", tenantId).param("p", p).query(Long.class).single() > 0)
                throw BusinessException.conflict("MANUAL_DAILY_LIMIT", "One hand-style broadcast a day on each platform, to protect your account");
        }
        if (queue.isEmpty() && !push) throw BusinessException.conflict("NOBODY_TO_SEND", "No one to send to right now");
        if (!queue.isEmpty() && !billing.affordable(tenantId, queue.size())) throw BusinessException.conflict("NO_CREDIT", "Not enough credit: this needs " + cost / 100.0 + " EGP");

        var prev = previousOrders(tenantId);
        UUID id = jdbc.sql("""
                INSERT INTO commerce.broadcasts (tenant_id, segment, segments, platforms, message, audience, sent_count, skipped_window, skipped_consent, created_by, mode, push, image_file_id, pending_count, status, variants)
                VALUES (:t, :s, CAST(:segs AS text[]), CAST(:plats AS text[]), :m, :a, 0, :w, 0, :u, 'FREE', :push, :img, :p, :st, CAST(:v AS jsonb)) RETURNING id
                """).param("t", tenantId).param("s", segs.iterator().next()).param("segs", pgArray(segs)).param("plats", pgArray(plats)).param("m", text).param("a", all.size()).param("w", (int) outside).param("u", actor)
                .param("push", push).param("img", r.imageFileId()).param("p", queue.size()).param("st", queue.isEmpty() ? "DONE" : "SENDING").param("v", variants.isEmpty() ? null : toJson(variants)).query(UUID.class).single();
        for (Aud q : queue) {
            Map<String, Object> vars = new LinkedHashMap<>(prev.getOrDefault(q.orderPhone() == null ? "" : q.orderPhone(), Map.of()));
            if (q.orders() > 0) vars.put("orders", String.valueOf(q.orders()));
            jdbc.sql("INSERT INTO commerce.broadcast_recipients (broadcast_id, channel, external_id, phone, name, gender, vars, route) VALUES (:b, :c, :e, :p, :n, :g, CAST(:v AS jsonb), :route)")
                    .param("route", q.route()).param("b", id).param("c", q.platform()).param("e", q.externalId()).param("p", "WHATSAPP".equals(q.platform()) ? q.key() : null).param("n", q.name() == null ? "" : q.name()).param("g", genderOf(q)).param("v", toJson(vars)).update();
        }

        int pushed = 0;
        if (push) {
            String title = r.pushTitle() == null || r.pushTitle().isBlank() ? shop : r.pushTitle().trim();
            for (var row : jdbc.sql(SegmentSql.BASE + "SELECT phone, name, uid, gender, segment, orders FROM s").param("t", tenantId).query().listOfRows()) {
                if (row.get("uid") == null || !segmentWanted(segs, (String) row.get("segment")) || !hasDevice((UUID) row.get("uid"))) continue;
                UUID uid = (UUID) row.get("uid");
                Map<String, Object> vars = new LinkedHashMap<>(prev.getOrDefault((String) row.get("phone"), Map.of()));
                vars.put("orders", String.valueOf(row.get("orders")));
                notifications.push(uid, tenantId, title.length() > 80 ? title.substring(0, 80) : title, personalize(pickVersion(text, variants, uid), (String) row.get("name"), shop, Gender.of(genderOfRow(row)), vars, uid.getMostSignificantBits()), "{\"type\":\"BROADCAST\"}");
                pushed++;
            }
            if (pushed > 0) notifications.dispatchSoon();
            jdbc.sql("UPDATE commerce.broadcasts SET push_sent = :p WHERE id = :i").param("p", pushed).param("i", id).update();
        }
        audit.record(actor, tenantId, "BROADCAST_SENT", "broadcast", id, "{\"segments\":" + toJson(segs) + ",\"platforms\":" + toJson(plats) + "}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("audience", all.size());
        out.put("reachable", queue.size());
        out.put("skipped", skipped);
        out.put("outsideWindow", outside);
        long manualCount = queue.stream().filter(a -> "MANUAL_STYLE".equals(a.route())).count();
        out.put("etaMinutes", eta(queue.size() - manualCount));
        out.put("manualStyle", manualCount);
        out.put("manualEtaDays", manualCount == 0 || manualAsked.isEmpty() ? 0.0
                : policies.manual().estimateDays(manualCount, unofficial.warmupDay(tenantId, manualAsked.iterator().next()), unofficial.sentToday(tenantId, manualAsked.iterator().next())));
        out.put("costMinor", cost);
        out.put("pushSent", pushed);
        return out;
    }

    private long eta(long pending) { return (long) Math.ceil(pending * ((gapMin + gapMax) / 2.0) / 60.0); }

    private static String pgArray(Set<String> values) { return "{" + String.join(",", values) + "}"; }

    String shopName(UUID tenantId) { return jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query(String.class).single(); }

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

    private static String genderOf(Aud a) {
        if (a.gender() != null) return a.gender();
        return GenderService.guess(a.name());   // null when unknown (the column holds MALE or FEMALE only)
    }

    private static String genderOfRow(Map<String, Object> row) {
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

    /**
     * One pass: sends the next waiting message of each running broadcast (one per pass, then the random gap). Returns how many went out. Public for tests.
     * A recipient is marked SENDING (and committed) before anything goes out; one found SENDING for over two minutes (the server stopped in between) goes back to the queue and its charge is given back.
     */
    public synchronized int runDue() {
        resetStale();
        int sent = 0;
        for (var b : jdbc.sql("SELECT id, tenant_id, message, image_file_id, next_send_at, variants::text AS variants, wa_seq, wa_since_break, wa_break_after, wa_last_hash FROM commerce.broadcasts WHERE status = 'SENDING' AND " + com.platform.shared.TenantState.running("tenant_id")
                + " AND EXISTS (SELECT 1 FROM commerce.broadcast_recipients r WHERE r.broadcast_id = broadcasts.id AND r.route = 'OFFICIAL' AND r.status IN ('PENDING', 'SENDING')) ORDER BY created_at LIMIT 20").query().listOfRows()) {
            UUID id = (UUID) b.get("id"), tenantId = (UUID) b.get("tenant_id");
            String text = (String) b.get("message");
            List<String> versions = new ArrayList<>();
            try { if (b.get("variants") != null) for (var v : JSON.readTree(b.get("variants").toString())) versions.add(v.asText()); } catch (Exception ignored) { /* no extra wordings */ }
            // 1) too many customers asked to stop: pause and let the owner look
            var stats = jdbc.sql("SELECT count(*) FILTER (WHERE opted_out_at IS NOT NULL) AS stops, count(*) FILTER (WHERE status = 'SENT') AS sent FROM commerce.broadcast_recipients WHERE broadcast_id = :b").param("b", id).query().listOfRows().get(0);
            long stops = num(stats.get("stops")), sentSoFar = num(stats.get("sent"));
            if (stops >= 3 && stops * 100 >= sentSoFar * 3) { jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'OPT_OUTS' WHERE id = :i").param("i", id).update(); continue; }
            // 2) quiet hours in the shop's own time: nobody gets a promotion at night
            if (quiet(tenantId)) { setReason(id, "QUIET_HOURS"); continue; }
            // 3) a random gap after every message, however long the list is: a fixed rhythm looks like a bot, and bursts get numbers banned
            if (b.get("next_send_at") != null && ((Timestamp) b.get("next_send_at")).toInstant().isAfter(Instant.now())) continue;
            clearReason(id, "QUIET_HOURS", "WA_ACTIVE_HOURS", "WA_HOURLY_CAP", "WA_DAILY_CAP");
            String shop = shopName(tenantId);
            var wa = policies.whatsapp();
            String imageUrl = b.get("image_file_id") == null ? null : urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId)) + "/api/bff/files/" + b.get("image_file_id") + "/content?variant=medium";
            for (int look = 0; look < 100; look++) {   // a contact skipped at the last moment costs no gap, so look on to the next one
                var rcp = jdbc.sql("SELECT id, channel, external_id, phone, name, gender, vars::text AS vars FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING' AND route = 'OFFICIAL' ORDER BY id LIMIT 1").param("b", id).query().listOfRows().stream().findFirst().orElse(null);
                if (rcp == null) break;
                UUID rid = (UUID) rcp.get("id");
                if ("WHATSAPP".equals(rcp.get("channel"))) {   // the WhatsApp profile's optional windows and caps (off by default: the owner's rule is the 5-15 s gap and the credit as the only limit)
                    String hold = waHold(tenantId, wa);
                    if (hold != null) { setReason(id, hold); break; }
                }
                if (jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'SENDING', sent_at = now(), error = NULL WHERE id = :i AND status = 'PENDING'").param("i", rid).update() == 0) continue;
                String channel = (String) rcp.get("channel"), to = (String) rcp.get("phone"), ext = (String) rcp.get("external_id");
                // the silent protection: someone who asked to stop, or whose platform window closed meanwhile, is not messaged
                String skip = skipReason(tenantId, channel, to, ext);
                if (skip != null) { finish(id, rid, "SKIPPED", skip, 0, "OUTSIDE_WINDOW".equals(skip)); continue; }
                boolean isWa = "WHATSAPP".equals(channel);
                if (isWa) {
                    // (j) health check before the send: the own number must still be linked. A disconnect mid-broadcast can be a ban or a logout: stop, do not keep hammering
                    if (!evolution.connected(tenantId)) { requeue(rid); jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'WHATSAPP_DISCONNECTED' WHERE id = :i").param("i", id).update(); break; }
                    // (k) the owner is chatting with this customer right now: a promotion in the middle of it would be rude and looks automated
                    if (ownerActive(tenantId, "WHATSAPP", digits(to), wa.profile().ownerReplyMinutes())) { finish(id, rid, "SKIPPED", "OWNER_ACTIVE", 0, false); continue; }
                }
                // the wording: WhatsApp rotates the wordings in turn and gets the tiny safe variations; the platform channels keep the fixed pick per customer
                String body = personalize(isWa ? HumanPolicy.rotate(text, versions, (int) num(b.get("wa_seq"))) : pickVersion(text, versions, rid), (String) rcp.get("name"), shop, Gender.of((String) rcp.get("gender")), parseVars(rcp.get("vars")), rid.getMostSignificantBits());
                String bodyHash = null;
                if (isWa) {
                    body = wa.vary(HumanPolicy.stripRepeatedLinks(body), rnd, (String) b.get("wa_last_hash"));   // never the same text twice in a row when we can help it
                    bodyHash = HumanPolicy.hash(body);
                    String why = wa.contentProblem(body);
                    if (why != null) { finish(id, rid, "SKIPPED", "CONTENT_GUARD", 0, false); continue; }   // not charged: nothing was sent
                }
                String ref = "bc-" + id + "-" + rid;
                if (!billing.charge(tenantId, ref, "Broadcast message")) {   // out of credit: back to the queue and pause until the shop tops up
                    requeue(rid);
                    jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'NO_CREDIT' WHERE id = :i").param("i", id).update();
                    break;
                }
                long price = billing.priceMinor();
                jdbc.sql("UPDATE commerce.broadcast_recipients SET cost_minor = :c WHERE id = :i").param("c", price).param("i", rid).update();
                boolean ok;
                String error = null;
                if (isWa) {
                    typing(tenantId, to, gapMin > 0 ? wa.typingMillis(body.length(), rnd, gapMin * 1000L) : 0);   // "typing..." for a moment, never longer than the gap
                    var d = evolution.deliver(tenantId, to, body + OPT_OUT_LINE, imageUrl);   // every message tells the customer how to stop, so they reply instead of reporting
                    if (d == EvolutionService.Delivery.DEFERRED) {   // at the per-minute limit: the rest waits for the next pass
                        billing.refund(tenantId, ref, "Broadcast message deferred");
                        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'PENDING', sent_at = NULL, cost_minor = 0 WHERE id = :i").param("i", rid).update();
                        break;
                    }
                    ok = d == EvolutionService.Delivery.SENT;
                    if (!ok) error = "SEND_FAILED";
                } else {
                    var o = meta.send(tenantId, channel, ext, body, imageUrl);
                    ok = o.ok();
                    error = o.error();
                }
                if (!ok) billing.refund(tenantId, ref, "Broadcast message not delivered");   // never charge for a message that was not sent
                finish(id, rid, ok ? "SENT" : "FAILED", error, ok ? price : 0, false);
                if (ok) sent++;
                // a random gap after every message (the owner's 5-15 s), and for WhatsApp a longer micro-break after every 40-80 messages
                long gapMs = wa.gapMillis(rnd, gapMin, gapMax);
                if (isWa && ok) {
                    int since = (int) num(b.get("wa_since_break")) + 1;
                    int after = b.get("wa_break_after") == null ? wa.nextBreakAfter(rnd) : (int) num(b.get("wa_break_after"));
                    int nextAfter = after;
                    if (since >= after) { gapMs = Math.max(gapMs, wa.breakMillis(rnd)); since = 0; nextAfter = wa.nextBreakAfter(rnd); }
                    jdbc.sql("UPDATE commerce.broadcasts SET wa_seq = wa_seq + 1, wa_last_hash = :h, wa_since_break = :s, wa_break_after = :a WHERE id = :i").param("h", bodyHash).param("s", since).param("a", nextAfter).param("i", id).update();
                }
                jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = now() + make_interval(secs => :g) WHERE id = :i").param("g", gapMs / 1000.0).param("i", id).update();
                // five failures in a row means something is wrong with the account: stop instead of hammering it
                int streak = jdbc.sql("UPDATE commerce.broadcasts SET consecutive_failures = CASE WHEN :ok THEN 0 ELSE consecutive_failures + 1 END WHERE id = :i RETURNING consecutive_failures").param("ok", ok).param("i", id).query(Integer.class).single();
                if (streak >= 5) jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'FAILURES' WHERE id = :i").param("i", id).update();
                // (i) a failed send plus a number that is no longer linked is the closest thing to a ban signal we can see: stop at once
                if (isWa && !ok && !evolution.connected(tenantId)) jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'WHATSAPP_DISCONNECTED' WHERE id = :i").param("i", id).update();
                break;
            }
            jdbc.sql("UPDATE commerce.broadcasts SET status = 'DONE' WHERE id = :i AND status = 'SENDING' AND NOT EXISTS (SELECT 1 FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status IN ('PENDING', 'SENDING'))").param("i", id).update();
        }
        return sent;
    }

    /** Settles one recipient: its final status, the counters of the broadcast, what was charged. */
    void finish(UUID broadcastId, UUID recipientId, String status, String error, long charged, boolean outsideWindow) {
        jdbc.sql("UPDATE commerce.broadcast_recipients SET status = :s, error = :e, sent_at = now(), cost_minor = :c WHERE id = :i").param("s", status).param("e", error).param("c", charged).param("i", recipientId).update();
        jdbc.sql("UPDATE commerce.broadcasts SET sent_count = sent_count + :ok, failed_count = failed_count + :bad, skipped_window = skipped_window + :w, pending_count = greatest(pending_count - 1, 0), charged_minor = charged_minor + :c WHERE id = :i")
                .param("ok", "SENT".equals(status) ? 1 : 0).param("bad", "FAILED".equals(status) ? 1 : 0).param("w", outsideWindow ? 1 : 0).param("c", charged).param("i", broadcastId).update();
    }

    /** A recipient that was marked SENDING but not attempted goes back to the queue. */
    void requeue(UUID recipientId) { jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'PENDING', sent_at = NULL, cost_minor = 0 WHERE id = :i").param("i", recipientId).update(); }

    /** Clears the pause reason only when it is one of the given ones: each lane clears its own, never the other lane's. */
    void clearReason(UUID id, String... reasons) {
        jdbc.sql("UPDATE commerce.broadcasts SET pause_reason = NULL WHERE id = :i AND pause_reason = ANY (CAST(:r AS text[]))").param("i", id).param("r", "{" + String.join(",", reasons) + "}").update();
    }

    java.time.ZonedDateTime localNow(UUID tenantId) { return java.time.ZonedDateTime.now(unofficial.zone(tenantId)); }

    /** The WhatsApp profile's optional active-hours window and hourly / daily caps (all off by default). Returns the pause reason or null. */
    private String waHold(UUID tenantId, HumanPolicy wa) {
        if (!wa.inActiveWindow(localNow(tenantId))) return "WA_ACTIVE_HOURS";
        if (wa.profile().hourlyCap() > 0 && !wa.hourlyAllows(sentSince(tenantId, "WHATSAPP", "1 hour"))) return "WA_HOURLY_CAP";
        if (wa.profile().maxDaily() > 0 && !wa.dailyAllows(1, sentSince(tenantId, "WHATSAPP", "24 hours"))) return "WA_DAILY_CAP";
        return null;
    }

    long sentSince(UUID tenantId, String channel, String interval) {
        return jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients r JOIN commerce.broadcasts b ON b.id = r.broadcast_id WHERE b.tenant_id = :t AND r.channel = :c AND r.status = 'SENT' AND r.sent_at > now() - CAST(:i AS interval)")
                .param("t", tenantId).param("c", channel).param("i", interval).query(Long.class).single();
    }

    /** True when the owner is dealing with this contact right now: the chat is taken over by a person, or a person (not the AI) wrote in it within the last {@code minutes}. */
    boolean ownerActive(UUID tenantId, String channel, String idOrDigits, int minutes) {
        String match = "WHATSAPP".equals(channel) ? "right(regexp_replace(c.external_id, '[^0-9]', '', 'g'), 9) = :e" : "c.external_id = :e";
        String e = "WHATSAPP".equals(channel) && idOrDigits != null ? idOrDigits.substring(Math.max(0, idOrDigits.length() - 9)) : idOrDigits;
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat.conversations c WHERE c.tenant_id = :t AND c.channel = :c AND " + match
                        + " AND (c.takeover OR EXISTS (SELECT 1 FROM chat.messages m WHERE m.conversation_id = c.id AND m.by_owner AND m.created_at > now() - make_interval(mins => :m))))")
                .param("t", tenantId).param("c", channel).param("e", e).param("m", Math.max(1, minutes)).query(Boolean.class).single();
    }

    /** Shows "typing..." on the customer's WhatsApp for a moment before the message (Evolution presence), and waits that long. Best effort: never fails a send. */
    private void typing(UUID tenantId, String phone, int ms) {
        if (ms <= 0) return;
        try {
            String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", tenantId).query(String.class).optional().orElse(null);
            if (inst != null) evolutionApi.call("POST", "/chat/sendPresence/" + inst, Map.of("number", digits(phone), "presence", "composing", "delay", ms));
            if (policies.whatsappTypingSleep()) Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("Typing presence not sent: {}", e.getMessage());
        }
    }

    /** Why this contact must not get the message right now, or null. */
    private String skipReason(UUID tenantId, String channel, String phone, String externalId) {
        if ("WHATSAPP".equals(channel)) {
            String d = digits(phone);
            boolean stopped = jdbc.sql("SELECT EXISTS (SELECT 1 FROM commerce.customers cu WHERE cu.tenant_id = :t AND cu.broadcast_opt_out_at IS NOT NULL AND right(regexp_replace(coalesce(cu.phone, ''), '[^0-9]', '', 'g'), 9) = :d)")
                    .param("t", tenantId).param("d", d.substring(Math.max(0, d.length() - 9))).query(Boolean.class).single();
            return stopped ? "OPTED_OUT" : null;
        }
        var c = jdbc.sql("SELECT broadcast_opt_out_at IS NOT NULL AS stopped, (last_customer_at > now() - interval '23 hours' OR window_until > now()) AS open FROM chat.conversations WHERE tenant_id = :t AND channel = :c AND external_id = :e")
                .param("t", tenantId).param("c", channel).param("e", externalId).query().listOfRows().stream().findFirst().orElse(null);
        if (c == null) return "NO_CONVERSATION";
        if (Boolean.TRUE.equals(c.get("stopped"))) return "OPTED_OUT";
        return Boolean.TRUE.equals(c.get("open")) ? null : "OUTSIDE_WINDOW";
    }

    /** A recipient never stays SENDING after a crash: after two minutes it goes back to the queue (a canceled broadcast stops it) and what it was charged is given back. */
    private void resetStale() {
        for (var r : jdbc.sql("SELECT r.id, r.broadcast_id, r.cost_minor, r.route, b.tenant_id, b.status FROM commerce.broadcast_recipients r JOIN commerce.broadcasts b ON b.id = r.broadcast_id WHERE r.status = 'SENDING' AND r.sent_at < now() - interval '2 minutes'").query().listOfRows()) {
            long cost = num(r.get("cost_minor"));
            UUID rid = (UUID) r.get("id"), bid = (UUID) r.get("broadcast_id");
            if (cost > 0) {
                billing.refund((UUID) r.get("tenant_id"), "bc-" + bid + "-" + rid, "Broadcast message interrupted");
                jdbc.sql("UPDATE commerce.broadcasts SET charged_minor = greatest(charged_minor - :c, 0) WHERE id = :b").param("c", cost).param("b", bid).update();
            }
            boolean canceled = "CANCELED".equals(r.get("status"));
            if ("MANUAL_STYLE".equals(r.get("route")) && !canceled) {   // a hand-style message may have gone out before the stop: never send it twice to a person, mark it failed instead
                jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'FAILED', error = 'INTERRUPTED', cost_minor = 0 WHERE id = :i AND status = 'SENDING'").param("i", rid).update();
                jdbc.sql("UPDATE commerce.broadcasts SET failed_count = failed_count + 1, pending_count = greatest(pending_count - 1, 0) WHERE id = :b").param("b", bid).update();
                continue;
            }
            jdbc.sql("UPDATE commerce.broadcast_recipients SET status = :s, sent_at = NULL, cost_minor = 0 WHERE id = :i AND status = 'SENDING'").param("s", canceled ? "STOPPED" : "PENDING").param("i", rid).update();
        }
    }

    static final String OPT_OUT_LINE = "\n\nلإيقاف الرسائل اكتب: إيقاف · Reply STOP to unsubscribe";

    void setReason(UUID id, String reason) { jdbc.sql("UPDATE commerce.broadcasts SET pause_reason = CAST(:r AS varchar) WHERE id = :i AND pause_reason IS DISTINCT FROM CAST(:r AS varchar)").param("r", reason).param("i", id).update(); }

    /** True during the shop's quiet hours (22:00 to 08:00 by default, in the shop's time zone). From and to being equal switches quiet hours off. */
    private boolean quiet(UUID tenantId) {
        if (quietFrom == quietTo) return false;
        int hour;
        try { hour = java.time.ZonedDateTime.now(java.time.ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single())).getHour(); }
        catch (RuntimeException e) { hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Cairo")).getHour(); }
        return quietFrom > quietTo ? (hour >= quietFrom || hour < quietTo) : (hour >= quietFrom && hour < quietTo);
    }

    /** The owner looked at a paused broadcast and wants it to go on (after a top-up when it ran out of credit). */
    public Map<String, Object> resume(UUID tenantId, UUID id) {
        var b = jdbc.sql("SELECT status, pause_reason FROM commerce.broadcasts WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElse(null);
        if (b == null || !"PAUSED".equals(b.get("status"))) throw BusinessException.conflict("INVALID_STATUS", "This broadcast is not paused");
        if (!billing.affordable(tenantId, 1)) throw BusinessException.conflict("NO_CREDIT", "Not enough credit: top up first");
        String manualChannel = jdbc.sql("SELECT channel FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND route = 'MANUAL_STYLE' AND status = 'PENDING' LIMIT 1").param("i", id).query(String.class).optional().orElse(null);
        if (manualChannel != null && List.of("ACCOUNT_BLOCK_SIGNAL", "SESSION_EXPIRED").contains((String) b.get("pause_reason")) && !unofficial.reachable(tenantId, manualChannel))
            throw BusinessException.conflict("SESSION_NOT_READY", "The hand-style account is not ready: wait out the pause and enable or log in again, or cancel this broadcast");
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

    private static List<String> split(Object csv) { return csv == null || csv.toString().isEmpty() ? List.of() : List.of(csv.toString().split(",")); }

    public List<Map<String, Object>> history(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT id, created_at, array_to_string(segments, ',') AS segs, array_to_string(platforms, ',') AS plats, message, status, pause_reason, audience, sent_count, failed_count, pending_count,
                       skipped_window, next_send_at, charged_minor, mode, push, push_sent
                FROM commerce.broadcasts WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 30
                """).param("t", tenantId).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("createdAt", instant(r.get("created_at")));
            m.put("segments", split(r.get("segs")));
            m.put("platforms", split(r.get("plats")));
            m.put("message", r.get("message"));
            m.put("status", r.get("status"));
            m.put("pauseReason", r.get("pause_reason"));
            m.put("audience", r.get("audience"));
            m.put("sentCount", r.get("sent_count"));
            m.put("failedCount", r.get("failed_count"));
            m.put("pendingCount", r.get("pending_count"));
            m.put("skippedOutsideWindow", r.get("skipped_window"));
            m.put("nextSendAt", instant(r.get("next_send_at")));
            m.put("etaMinutes", "SENDING".equals(r.get("status")) || "PAUSED".equals(r.get("status")) ? eta(num(r.get("pending_count"))) : 0);
            m.put("chargedMinor", r.get("charged_minor"));
            m.put("mode", r.get("mode"));
            m.put("push", r.get("push"));
            m.put("pushSent", r.get("push_sent"));
            out.add(m);
        }
        return out;
    }

    /**
     * The people of one broadcast: the one being sent now first, then the waiting ones in sending order (by id), then the finished ones (SENT, FAILED, SKIPPED, STOPPED) newest first.
     */
    public Map<String, Object> recipients(UUID tenantId, UUID broadcastId, String status, int limit, int offset) {
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE id = :i AND tenant_id = :t").param("i", broadcastId).param("t", tenantId).query(Long.class).single() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (st != null && !List.of("PENDING", "SENDING", "SENT", "FAILED", "STOPPED", "SKIPPED").contains(st)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("PENDING", "SENDING", "SENT", "FAILED", "STOPPED", "SKIPPED")) counts.put(k, 0L);
        for (var r : jdbc.sql("SELECT status, count(*) AS n FROM commerce.broadcast_recipients WHERE broadcast_id = :b GROUP BY status").param("b", broadcastId).query().listOfRows()) counts.put((String) r.get("status"), num(r.get("n")));
        long total = st == null ? counts.values().stream().mapToLong(v -> (Long) v).sum() : (Long) counts.get(st);
        List<Map<String, Object>> data = new ArrayList<>();
        for (var r : jdbc.sql("""
                SELECT id, name, channel, phone, status, error, sent_at, cost_minor, route FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))
                ORDER BY CASE status WHEN 'SENDING' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END, CASE WHEN status = 'PENDING' THEN id END, sent_at DESC NULLS LAST, id
                LIMIT :l OFFSET :o
                """).param("b", broadcastId).param("s", st).param("l", Math.max(1, Math.min(limit, 500))).param("o", Math.max(0, offset)).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("name", r.get("name"));
            m.put("channel", r.get("channel"));
            m.put("phone", r.get("phone"));
            m.put("status", r.get("status"));
            m.put("error", r.get("error"));
            m.put("sentAt", instant(r.get("sent_at")));
            m.put("costMinor", r.get("cost_minor"));
            m.put("route", r.get("route"));
            data.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }
}
