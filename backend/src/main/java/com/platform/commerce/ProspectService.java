package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.OutboundBilling;
import com.platform.billing.WalletService;
import com.platform.chat.unofficial.HumanPolicies;
import com.platform.chat.unofficial.HumanPolicy;
import com.platform.chat.unofficial.IgBridgeClient;
import com.platform.chat.unofficial.OutOfWindowSender;
import com.platform.chat.unofficial.UnofficialMetaService;
import com.platform.shared.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "New customers": people who follow or commented on the shop's Instagram / Facebook page but never wrote to it. They are collected (Instagram followers and commenters through the
 * ig-bridge, Facebook commenters from the comments we already receive; Facebook followers cannot be listed by any tool), shown, and messaged hand-style (route MANUAL_STYLE) on the
 * stricter cold profile of {@link HumanPolicies}. Anyone who already has a conversation with the page is never a prospect (they belong to the existing-customers tab).
 *
 * One sync at a time per shop, paced with random sleeps, once a day on a schedule plus "sync now". Verified only against a fake bridge.
 */
@Service
public class ProspectService {
    private static final Logger log = LoggerFactory.getLogger(ProspectService.class);
    static final List<String> PLATFORMS = List.of("INSTAGRAM", "FACEBOOK");
    static final List<String> SOURCES = List.of("FOLLOWER", "COMMENTER");
    static final List<String> STATUSES = List.of("NEW", "CONTACTED", "REPLIED", "OPTED_OUT", "BLOCKED", "NOT_FOUND");
    /** Someone who already has a conversation with the page is an existing customer, not a prospect. */
    private static final String NOT_A_CUSTOMER = " AND NOT EXISTS (SELECT 1 FROM chat.conversations cv WHERE cv.tenant_id = p.tenant_id AND cv.channel = p.platform AND (cv.external_id = p.external_ref"
            + " OR (p.username IS NOT NULL AND lower(cv.display_name) = lower(p.username))))";
    private static final String ELIGIBLE = " AND (p.status = 'NEW' OR (p.status = 'CONTACTED' AND p.contacted_at < now() - make_interval(days => :days)))";

    private final JdbcClient jdbc;
    private final UnofficialMetaService unofficial;
    private final IgBridgeClient ig;
    private final OutboundBilling billing;
    private final WalletService wallet;
    private final HumanPolicies policies;
    private final AuditService audit;
    private final boolean scheduler, async;
    private final int maxFollowers, pageSize, sleepMin, sleepMax, recentPosts;
    private final Random rnd = new Random();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "prospect-sync"); t.setDaemon(true); return t; });

    public ProspectService(JdbcClient jdbc, UnofficialMetaService unofficial, IgBridgeClient ig, OutboundBilling billing, WalletService wallet, HumanPolicies policies, AuditService audit,
                           @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler, @Value("${app.prospects.async:true}") boolean async,
                           @Value("${app.prospects.max-followers:3000}") int maxFollowers, @Value("${app.prospects.page-size:100}") int pageSize,
                           @Value("${app.prospects.page-sleep-min-ms:2500}") int sleepMin, @Value("${app.prospects.page-sleep-max-ms:6000}") int sleepMax, @Value("${app.prospects.recent-posts:10}") int recentPosts) {
        this.jdbc = jdbc;
        this.unofficial = unofficial;
        this.ig = ig;
        this.billing = billing;
        this.wallet = wallet;
        this.policies = policies;
        this.audit = audit;
        this.scheduler = scheduler;
        this.async = async;
        this.maxFollowers = Math.max(1, maxFollowers);
        this.pageSize = Math.max(1, Math.min(200, pageSize));
        this.sleepMin = Math.max(0, sleepMin);
        this.sleepMax = Math.max(this.sleepMin, sleepMax);
        this.recentPosts = Math.max(1, Math.min(30, recentPosts));
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }

    private static Instant instant(Object ts) { return ts instanceof Timestamp t ? t.toInstant() : null; }

    private static String platform(String p) {
        String v = p == null ? "" : p.trim().toUpperCase();
        if ("MESSENGER".equals(v)) v = "FACEBOOK";
        if (!PLATFORMS.contains(v)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown platform");
        return v;
    }

    private static String clip(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }

    private static String pgArray(Set<String> values) { return "{" + String.join(",", values) + "}"; }

    private int coldDays() { return Math.max(1, policies.cold().profile().sameContactDays()); }

    // ---- listing --------------------------------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, String platformIn, String sourceIn, String q, String statusIn, int limit, int offset) {
        String platform = platformIn == null || platformIn.isBlank() ? null : platform(platformIn);
        String source = sourceIn == null || sourceIn.isBlank() ? null : sourceIn.trim().toUpperCase();
        if (source != null && !SOURCES.contains(source)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown source");
        String status = statusIn == null || statusIn.isBlank() ? null : statusIn.trim().toUpperCase();
        if (status != null && !STATUSES.contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown status");
        String needle = q == null ? "" : q.trim();
        String where = " FROM commerce.prospects p WHERE p.tenant_id = :t" + NOT_A_CUSTOMER;
        Map<String, Object> counts = new LinkedHashMap<>();
        Map<String, Object> igCounts = new LinkedHashMap<>(), fbCounts = new LinkedHashMap<>();
        igCounts.put("FOLLOWER", 0L);
        igCounts.put("COMMENTER", 0L);
        fbCounts.put("COMMENTER", 0L);
        counts.put("INSTAGRAM", igCounts);
        counts.put("FACEBOOK", fbCounts);
        for (var r : jdbc.sql("SELECT p.platform, p.source, count(*) AS n" + where + " GROUP BY p.platform, p.source").param("t", tenantId).query().listOfRows()) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) counts.get(r.get("platform"));
            if (m != null && m.containsKey(r.get("source"))) m.put((String) r.get("source"), num(r.get("n")));
        }
        StringBuilder f = new StringBuilder(where);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        if (platform != null) { f.append(" AND p.platform = :pl"); params.put("pl", platform); }
        if (source != null) { f.append(" AND p.source = :src"); params.put("src", source); }
        if (status != null) { f.append(" AND p.status = :st"); params.put("st", status); }
        if (!needle.isEmpty()) { f.append(" AND (p.username ILIKE :q OR p.display_name ILIKE :q)"); params.put("q", "%" + needle.replace("%", "").replace("_", "\\_") + "%"); }
        long total = jdbc.sql("SELECT count(*)" + f).params(params).query(Long.class).single();
        params.put("l", Math.max(1, Math.min(limit, 200)));
        params.put("o", Math.max(0, offset));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var r : jdbc.sql("SELECT p.id, p.platform, p.username, p.display_name, p.source, p.post_ref, p.last_comment, p.is_private, p.status, p.first_seen_at" + f + " ORDER BY p.first_seen_at DESC, p.id LIMIT :l OFFSET :o").params(params).query().listOfRows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("platform", r.get("platform"));
            m.put("username", r.get("username") != null ? r.get("username") : r.get("display_name"));
            m.put("displayName", r.get("display_name"));
            m.put("source", r.get("source"));
            m.put("postRef", r.get("post_ref"));
            m.put("lastComment", r.get("last_comment"));
            m.put("isPrivate", r.get("is_private"));
            m.put("status", r.get("status"));
            m.put("firstSeenAt", instant(r.get("first_seen_at")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("counts", counts);
        out.put("items", items);
        return out;
    }

    // ---- sync -----------------------------------------------------------------------------------------------------------------------

    /** Starts a sync (or returns the one already running for this shop: one at a time). */
    public synchronized Map<String, Object> startSync(UUID tenantId, String platformIn) {
        String platform = platform(platformIn);
        if ("INSTAGRAM".equals(platform) && !"ACTIVE".equals(unofficial.status(tenantId, "INSTAGRAM"))) throw BusinessException.conflict("NEEDS_LOGIN", "Connect the Instagram account first");
        jdbc.sql("UPDATE commerce.prospect_syncs SET status = 'FAILED', error = 'INTERRUPTED', finished_at = now() WHERE tenant_id = :t AND status = 'RUNNING' AND started_at < now() - interval '30 minutes'").param("t", tenantId).update();
        var running = jdbc.sql("SELECT id FROM commerce.prospect_syncs WHERE tenant_id = :t AND status = 'RUNNING' ORDER BY started_at DESC LIMIT 1").param("t", tenantId).query(UUID.class).optional();
        if (running.isPresent()) return Map.of("syncId", running.get(), "status", "RUNNING");
        UUID id = jdbc.sql("INSERT INTO commerce.prospect_syncs (tenant_id, platform) VALUES (:t, :p) RETURNING id").param("t", tenantId).param("p", platform).query(UUID.class).single();
        if (async) pool.submit(() -> runSync(id, tenantId, platform));
        else runSync(id, tenantId, platform);
        String st = jdbc.sql("SELECT status FROM commerce.prospect_syncs WHERE id = :i").param("i", id).query(String.class).single();
        return Map.of("syncId", id, "status", st);
    }

    public Map<String, Object> syncStatus(UUID tenantId, String platformIn) {
        String platform = platform(platformIn);
        var last = jdbc.sql("SELECT status, fetched, total, error, started_at, finished_at FROM commerce.prospect_syncs WHERE tenant_id = :t AND platform = :p ORDER BY started_at DESC LIMIT 1")
                .param("t", tenantId).param("p", platform).query().listOfRows().stream().findFirst().orElse(null);
        Instant lastDone = jdbc.sql("SELECT max(finished_at) FROM commerce.prospect_syncs WHERE tenant_id = :t AND platform = :p AND status = 'DONE'").param("t", tenantId).param("p", platform).query((rs, i) -> rs.getTimestamp(1)).optional()
                .map(Timestamp::toInstant).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", last == null ? "IDLE" : last.get("status"));
        out.put("fetched", last == null ? 0 : last.get("fetched"));
        out.put("total", last == null ? 0 : last.get("total"));
        out.put("error", last == null ? null : last.get("error"));
        out.put("startedAt", last == null ? null : instant(last.get("started_at")));
        out.put("finishedAt", last == null ? null : instant(last.get("finished_at")));
        out.put("lastSyncedAt", lastDone);
        return out;
    }

    /** Thrown inside a sync to end it with a stable error code. */
    private static final class SyncError extends RuntimeException {
        final String code;
        SyncError(String code) { super(code, null, false, false); this.code = code; }
    }

    void runSync(UUID syncId, UUID tenantId, String platform) {
        try {
            if ("INSTAGRAM".equals(platform)) syncInstagram(syncId, tenantId); else syncFacebook(syncId, tenantId);
            jdbc.sql("UPDATE commerce.prospect_syncs SET status = 'DONE', total = fetched, finished_at = now() WHERE id = :i").param("i", syncId).update();
        } catch (SyncError e) {
            if ("NEEDS_LOGIN".equals(e.code)) unofficial.markNeedsLogin(tenantId, platform, "SESSION_EXPIRED");
            jdbc.sql("UPDATE commerce.prospect_syncs SET status = 'FAILED', error = :e, finished_at = now() WHERE id = :i").param("e", e.code).param("i", syncId).update();
        } catch (RuntimeException e) {
            log.warn("Prospect sync failed: {}", e.getClass().getSimpleName());
            jdbc.sql("UPDATE commerce.prospect_syncs SET status = 'FAILED', error = 'FAILED', finished_at = now() WHERE id = :i").param("i", syncId).update();
        }
    }

    private static String syncCode(String bridgeError) {
        return switch (bridgeError == null ? "" : bridgeError) {
            case "SESSION_EXPIRED" -> "NEEDS_LOGIN";
            case "CHALLENGE" -> "CHALLENGE_REQUIRED";
            case "UNREACHABLE" -> "BRIDGE_UNREACHABLE";
            case "RATE_LIMITED" -> "RATE_LIMITED";
            default -> "FAILED";
        };
    }

    private void progress(UUID syncId, int fetched, int total) {
        jdbc.sql("UPDATE commerce.prospect_syncs SET fetched = :f, total = :t WHERE id = :i").param("f", fetched).param("t", Math.max(total, fetched)).param("i", syncId).update();
    }

    private void pause() {
        if (sleepMax <= 0) return;
        try { Thread.sleep(sleepMin + rnd.nextInt(sleepMax - sleepMin + 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SyncError("INTERRUPTED"); }
    }

    private void syncInstagram(UUID syncId, UUID tenantId) {
        Set<String> own = new LinkedHashSet<>(jdbc.sql("SELECT external_id FROM chat.channels WHERE tenant_id = :t AND platform = 'INSTAGRAM'").param("t", tenantId).query(String.class).list());
        int fetched = 0;
        String cursor = null;
        while (fetched < maxFollowers) {
            var page = ig.followers(tenantId, pageSize, cursor);
            if (!page.ok()) throw new SyncError(syncCode(page.error()));
            for (var u : page.users()) if (!own.contains(u.pk())) upsert(tenantId, "INSTAGRAM", u.pk(), u.username(), u.fullName(), "FOLLOWER", null, null, u.isPrivate());
            fetched += page.users().size();
            cursor = page.nextCursor();
            progress(syncId, fetched, cursor == null ? fetched : Math.min(maxFollowers, fetched + pageSize));
            if (cursor == null || page.users().isEmpty()) break;
            pause();
        }
        var media = ig.recentMedia(tenantId, recentPosts);
        if (!media.ok()) throw new SyncError(syncCode(media.error()));
        for (var m : media.media()) {
            pause();
            var cs = ig.comments(tenantId, m.id(), 100);
            if (!cs.ok()) throw new SyncError(syncCode(cs.error()));
            for (var c : cs.comments()) {
                var u = c.user();
                if (own.contains(u.pk())) continue;
                upsert(tenantId, "INSTAGRAM", u.pk(), u.username(), u.fullName(), "COMMENTER", m.code() != null ? m.code() : m.id(), c.text(), u.isPrivate());
            }
            fetched += cs.comments().size();
            progress(syncId, fetched, fetched);
        }
    }

    /** Facebook cannot list followers; the commenters are the authors of the comments the page already received. */
    private void syncFacebook(UUID syncId, UUID tenantId) {
        Set<String> own = new LinkedHashSet<>(jdbc.sql("SELECT external_id FROM chat.channels WHERE tenant_id = :t AND platform = 'FACEBOOK'").param("t", tenantId).query(String.class).list());
        int n = 0;
        for (var r : jdbc.sql("""
                SELECT author_id, (array_agg(author_name ORDER BY created_at DESC))[1] AS name, (array_agg(text ORDER BY created_at DESC))[1] AS last, (array_agg(post_id ORDER BY created_at DESC))[1] AS post
                FROM chat.social_comments WHERE tenant_id = :t AND platform = 'FACEBOOK' AND author_id IS NOT NULL AND author_id <> '' GROUP BY author_id
                """).param("t", tenantId).query().listOfRows()) {
            String id = (String) r.get("author_id");
            if (own.contains(id)) continue;
            upsert(tenantId, "FACEBOOK", id, null, (String) r.get("name"), "COMMENTER", (String) r.get("post"), (String) r.get("last"), false);
            n++;
        }
        progress(syncId, n, n);
    }

    void upsert(UUID tenantId, String platform, String ref, String username, String displayName, String source, String postRef, String lastComment, boolean isPrivate) {
        if (ref == null || ref.isBlank()) return;
        boolean commenter = "COMMENTER".equals(source);
        jdbc.sql("""
                INSERT INTO commerce.prospects (tenant_id, platform, external_ref, username, display_name, source, post_ref, last_comment, is_private)
                VALUES (:t, :p, :r, :u, :n, :s, :post, :c, :priv)
                ON CONFLICT (tenant_id, platform, external_ref) DO UPDATE SET last_seen_at = now(),
                    username = coalesce(EXCLUDED.username, commerce.prospects.username),
                    display_name = coalesce(nullif(EXCLUDED.display_name, ''), commerce.prospects.display_name),
                    is_private = EXCLUDED.is_private,
                    source = CASE WHEN :commenter THEN 'COMMENTER' ELSE commerce.prospects.source END,
                    post_ref = CASE WHEN :commenter THEN EXCLUDED.post_ref ELSE commerce.prospects.post_ref END,
                    last_comment = CASE WHEN :commenter THEN EXCLUDED.last_comment ELSE commerce.prospects.last_comment END
                """).param("t", tenantId).param("p", platform).param("r", clip(ref, 120)).param("u", clip(username, 120)).param("n", clip(displayName, 200)).param("s", source)
                .param("post", clip(postRef, 200)).param("c", clip(lastComment, 500)).param("priv", isPrivate).param("commenter", commenter).update();
    }

    /** Once a day: shops with a connected Instagram account (and shops with Facebook comments) get their prospects refreshed in the background. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void scheduled() {
        if (!scheduler) return;
        try { runDaily(); } catch (RuntimeException e) { log.warn("Daily prospect sync failed: {}", e.getMessage()); }
    }

    /** Public for tests. Returns how many syncs were started. */
    public int runDaily() {
        int started = 0;
        for (String p : PLATFORMS) {
            String sql = "INSTAGRAM".equals(p)
                    ? "SELECT tenant_id FROM chat.unofficial_sessions WHERE platform = 'INSTAGRAM' AND status = 'ACTIVE'"
                    : "SELECT DISTINCT tenant_id FROM chat.social_comments WHERE platform = 'FACEBOOK' AND author_id IS NOT NULL";
            for (UUID t : jdbc.sql(sql).query(UUID.class).list()) {
                boolean fresh = jdbc.sql("SELECT EXISTS (SELECT 1 FROM commerce.prospect_syncs WHERE tenant_id = :t AND platform = :p AND started_at > now() - interval '23 hours')").param("t", t).param("p", p).query(Boolean.class).single();
                if (fresh) continue;
                try { startSync(t, p); started++; } catch (RuntimeException e) { log.debug("Daily prospect sync skipped: {}", e.getMessage()); }
            }
        }
        return started;
    }

    // ---- the broadcast --------------------------------------------------------------------------------------------------------------

    private record Pick(UUID id, String platform, String username, String name) {}

    /** The people a request would reach: eligible (new, or contacted long enough ago), not existing customers, matching platforms / sources / search / picked ids. */
    private List<Pick> pick(UUID tenantId, ProspectController.BroadcastReq r, Set<String> plats) {
        Set<String> sources = new LinkedHashSet<>();
        if (r.sources() != null) for (String s : r.sources()) {
            String v = s == null ? "" : s.trim().toUpperCase();
            if (!SOURCES.contains(v)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown source");
            sources.add(v);
        }
        boolean all = Boolean.TRUE.equals(r.all());
        List<UUID> ids = r.ids() == null ? List.of() : r.ids();
        if (!all && ids.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose who to send to");
        StringBuilder sql = new StringBuilder("SELECT p.id, p.platform, p.username, p.display_name FROM commerce.prospects p WHERE p.tenant_id = :t AND p.platform = ANY (CAST(:plats AS text[]))" + ELIGIBLE + NOT_A_CUSTOMER);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("t", tenantId);
        params.put("plats", pgArray(plats));
        params.put("days", coldDays());
        if (!sources.isEmpty()) { sql.append(" AND p.source = ANY (CAST(:srcs AS text[]))"); params.put("srcs", pgArray(sources)); }
        if (!all) { sql.append(" AND p.id = ANY (CAST(:ids AS uuid[]))"); params.put("ids", "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}"); }
        if (r.excludeIds() != null && !r.excludeIds().isEmpty()) { sql.append(" AND NOT (p.id = ANY (CAST(:ex AS uuid[])))"); params.put("ex", "{" + String.join(",", r.excludeIds().stream().map(UUID::toString).toList()) + "}"); }
        if (r.q() != null && !r.q().isBlank()) { sql.append(" AND (p.username ILIKE :q OR p.display_name ILIKE :q)"); params.put("q", "%" + r.q().trim().replace("%", "") + "%"); }
        sql.append(" ORDER BY p.first_seen_at, p.id LIMIT 20000");
        List<Pick> out = new ArrayList<>();
        for (var row : jdbc.sql(sql.toString()).params(params).query().listOfRows()) {
            String u = (String) row.get("username"), dn = (String) row.get("display_name");
            out.add(new Pick((UUID) row.get("id"), (String) row.get("platform"), u, dn != null && !dn.isBlank() ? dn : u));
        }
        return out;
    }

    private Set<String> platforms(ProspectController.BroadcastReq r) {
        Set<String> plats = new LinkedHashSet<>();
        if (r.platforms() != null) for (String p : r.platforms()) plats.add(platform(p));
        if (plats.isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose at least one platform");
        return plats;
    }

    public Map<String, Object> quote(UUID tenantId, ProspectController.BroadcastReq r) {
        Set<String> plats = platforms(r);
        long n = pick(tenantId, r, plats).size();
        var pr = policies.cold().profile();
        double perMessageSeconds = (pr.gapMinSeconds() + pr.gapMaxSeconds()) / 2.0 + pr.jitterMaxSeconds() / 2.0;
        long cost = n * billing.priceMinor(), balance = wallet.balance(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reachable", n);
        out.put("costMinor", cost);
        out.put("etaMinutes", (long) Math.ceil(n * perMessageSeconds / 60.0));
        out.put("etaDays", n == 0 ? 0.0 : policies.cold().estimateDays(n, 1, 0));
        out.put("balanceMinor", balance);
        out.put("enoughCredit", balance >= cost);
        return out;
    }

    public Map<String, Object> broadcast(UUID tenantId, UUID actor, ProspectController.BroadcastReq r) {
        Set<String> plats = platforms(r);
        HumanPolicy cold = policies.cold();
        String text = r.message() == null ? "" : r.message().trim();
        if (text.length() < 3 || text.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "The message must be 3 to 1000 characters");
        List<String> variants = new ArrayList<>();
        if (r.variants() != null) for (String v : r.variants()) { String t = v == null ? "" : v.trim(); if (!t.isEmpty()) variants.add(t); }
        if (variants.size() > 5) throw BusinessException.badRequest("VALIDATION_ERROR", "At most 5 other wordings");
        for (String v : variants) if (v.length() < 3 || v.length() > 1000) throw BusinessException.badRequest("VALIDATION_ERROR", "Each wording must be 3 to 1000 characters");
        if (r.imageFileId() != null && jdbc.sql("SELECT count(*) FROM core.files WHERE id = :f AND tenant_id = :t AND category = 'PRODUCT_IMAGE'").param("f", r.imageFileId()).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.badRequest("VALIDATION_ERROR", "The picture was not found");
        List<Pick> picks = pick(tenantId, r, plats);
        Set<String> used = new LinkedHashSet<>();
        for (Pick p : picks) used.add(p.platform());
        for (String p : used) if (!unofficial.reachable(tenantId, p)) throw BusinessException.conflict("CONNECT_ACCOUNT", "Connect the " + p.toLowerCase() + " account first");
        if (picks.isEmpty()) throw BusinessException.conflict("NOBODY_TO_SEND", "No one to send to right now");
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND status = 'SENDING'").param("t", tenantId).query(Long.class).single() > 0)
            throw BusinessException.conflict("BROADCAST_RUNNING", "A broadcast is still being sent. Wait until it finishes to protect your accounts");
        if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE tenant_id = :t AND created_at > now() - interval '24 hours'").param("t", tenantId).query(Long.class).single() >= 3)
            throw BusinessException.conflict("BROADCAST_LIMIT", "Three broadcasts a day at most, to protect your accounts from being reported as spam");
        List<String> raw = new ArrayList<>(variants);
        raw.add(0, text);
        for (String v : raw) {
            String why = cold.contentProblem(HumanPolicy.stripRepeatedLinks(v) + SegmentService.OPT_OUT_LINE);
            if (why != null) throw BusinessException.badRequest("CONTENT_REJECTED", "This message cannot be sent: " + why);
        }
        if (!cold.enoughVariants(text, variants)) throw BusinessException.badRequest("VARIANTS_REQUIRED", "Sending to new people needs at least " + cold.profile().minVariants() + " different wordings of the message");
        for (String p : used) {   // one cold broadcast a day per platform
            if (jdbc.sql("""
                    SELECT count(*) FROM commerce.broadcasts b WHERE b.tenant_id = :t AND b.kind = 'PROSPECTS' AND b.created_at > now() - interval '24 hours'
                       AND EXISTS (SELECT 1 FROM commerce.broadcast_recipients br WHERE br.broadcast_id = b.id AND br.channel = :p AND (br.status = 'SENT' OR b.status IN ('SENDING', 'PAUSED', 'DONE')))
                    """).param("t", tenantId).param("p", p).query(Long.class).single() > 0)
                throw BusinessException.conflict("MANUAL_DAILY_LIMIT", "One broadcast a day to new people on each platform, to protect your account");
        }
        long cost = picks.size() * billing.priceMinor();
        if (!billing.affordable(tenantId, picks.size())) throw BusinessException.conflict("NO_CREDIT", "Not enough credit: this needs " + cost / 100.0 + " EGP");

        UUID id = jdbc.sql("""
                INSERT INTO commerce.broadcasts (tenant_id, segment, segments, platforms, message, audience, sent_count, skipped_window, skipped_consent, created_by, mode, push, image_file_id, pending_count, status, variants, kind)
                VALUES (:t, 'ALL', CAST('{ALL}' AS text[]), CAST(:plats AS text[]), :m, :a, 0, 0, 0, :u, 'FREE', FALSE, :img, :p, 'SENDING', CAST(:v AS jsonb), 'PROSPECTS') RETURNING id
                """).param("t", tenantId).param("plats", pgArray(used)).param("m", text).param("a", picks.size()).param("u", actor).param("img", r.imageFileId()).param("p", picks.size())
                .param("v", variants.isEmpty() ? null : toJson(variants)).query(UUID.class).single();
        for (Pick p : picks) {
            jdbc.sql("INSERT INTO commerce.broadcast_recipients (broadcast_id, channel, external_id, phone, name, vars, route) SELECT :b, p.platform, p.external_ref, NULL, :n, CAST('{}' AS jsonb), 'MANUAL_STYLE' FROM commerce.prospects p WHERE p.id = :i")
                    .param("b", id).param("n", p.name() == null ? "" : p.name()).param("i", p.id()).update();
        }
        audit.record(actor, tenantId, "PROSPECT_BROADCAST_SENT", "broadcast", id, "{\"platforms\":" + toJson(used) + ",\"count\":" + picks.size() + "}");
        String first = used.iterator().next();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("kind", "PROSPECTS");
        out.put("audience", picks.size());
        out.put("reachable", picks.size());
        out.put("skipped", 0);
        out.put("outsideWindow", 0);
        out.put("connectAccount", 0);
        out.put("etaMinutes", 0);
        out.put("manualStyle", picks.size());
        out.put("manualEtaDays", cold.estimateDays(picks.size(), unofficial.warmupDay(tenantId, first), unofficial.sentToday(tenantId, first)));
        out.put("costMinor", cost);
        out.put("pushSent", 0);
        return out;
    }

    private static String toJson(Object o) { try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }

    // ---- used by ManualStyleRunner ---------------------------------------------------------------------------------------------------

    /** Why this prospect must not get a cold message right now (checked again at the moment of sending), or null. */
    String skipReason(UUID tenantId, String platform, String ref, HumanPolicy pol) {
        var p = jdbc.sql("SELECT pr.status, pr.contacted_at > now() - make_interval(days => :d) AS recent, "
                + "EXISTS (SELECT 1 FROM chat.conversations cv WHERE cv.tenant_id = :t AND cv.channel = :p AND (cv.external_id = :r OR (pr.username IS NOT NULL AND lower(cv.display_name) = lower(pr.username)))) AS customer "
                + "FROM commerce.prospects pr WHERE pr.tenant_id = :t AND pr.platform = :p AND pr.external_ref = :r").param("t", tenantId).param("p", platform).param("r", ref).param("d", Math.max(1, pol.profile().sameContactDays()))
                .query().listOfRows().stream().findFirst().orElse(null);
        if (p == null) return "NO_PROSPECT";
        if (Boolean.TRUE.equals(p.get("customer"))) return "ALREADY_CUSTOMER";   // they wrote to the page meanwhile: the inbox handles them now
        String st = (String) p.get("status");
        if ("OPTED_OUT".equals(st)) return "OPTED_OUT";
        if ("BLOCKED".equals(st) || "NOT_FOUND".equals(st)) return st;
        if ("CONTACTED".equals(st) && Boolean.TRUE.equals(p.get("recent"))) return "MANUAL_RECENT";
        return null;
    }

    /** What happened to the one message: CONTACTED, NOT_FOUND, or BLOCKED (this person triggered a block signal and is never retried). */
    void markResult(UUID tenantId, String platform, String ref, OutOfWindowSender.Kind kind) {
        String status = switch (kind) { case SENT -> "CONTACTED"; case NOT_FOUND -> "NOT_FOUND"; case BLOCKED -> "BLOCKED"; default -> null; };
        if (status == null) return;
        jdbc.sql("UPDATE commerce.prospects SET status = :s, contacted_at = CASE WHEN :s = 'CONTACTED' THEN now() ELSE contacted_at END WHERE tenant_id = :t AND platform = :p AND external_ref = :r")
                .param("s", status).param("t", tenantId).param("p", platform).param("r", ref).update();
    }
}
