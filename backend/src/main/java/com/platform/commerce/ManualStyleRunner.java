package com.platform.commerce;

import com.platform.billing.OutboundBilling;
import com.platform.chat.unofficial.HumanPolicies;
import com.platform.chat.unofficial.HumanPolicy;
import com.platform.chat.unofficial.OutOfWindowSender;
import com.platform.chat.unofficial.UnofficialMetaService;
import com.platform.notifications.NotificationService;
import com.platform.shared.Gender;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The "manual style" lane of a broadcast: contacts outside Meta's 24 h window, reached through the shop's own unofficial session, one at a time and slowly, the way a person
 * sends by hand (see {@link HumanPolicy}). It runs on its own schedule, apart from {@link SegmentService#runDue()}, so a slow message never delays a WhatsApp broadcast.
 *
 * Rules, in the order they are checked on every pass: too many STOP replies, the session (ACTIVE or the broadcast pauses), active hours (09:00-21:00 in the shop's time),
 * the random gap and the break after every 8-15 messages, the hourly cap, the warm-up daily cap, the bridge health check, then per contact: opted out, window opened again,
 * the owner chatting with them, the audience limits (age, at least 2 messages, not messaged by this route in 30 days), the content rules, credit. The FIRST block signal stops
 * everything: the session is blocked for {@code block-hours}, the broadcast pauses with ACCOUNT_BLOCK_SIGNAL and the owner is told. There is no automatic retry loop.
 *
 * Honest limit: verified only against a fake sender; never run against a real Instagram account.
 */
@Service
public class ManualStyleRunner {
    private static final Logger log = LoggerFactory.getLogger(ManualStyleRunner.class);
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._]{2,30}$");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcClient jdbc;
    private final SegmentService segments;
    private final UnofficialMetaService unofficial;
    private final HumanPolicies policies;
    private final OutboundBilling billing;
    private final NotificationService notifications;
    private final boolean scheduler;
    private final Random rnd = new Random();

    public ManualStyleRunner(JdbcClient jdbc, SegmentService segments, UnofficialMetaService unofficial, HumanPolicies policies, OutboundBilling billing, NotificationService notifications,
                             @Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.segments = segments;
        this.unofficial = unofficial;
        this.policies = policies;
        this.billing = billing;
        this.notifications = notifications;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 45_000)
    public void scheduled() {
        if (!scheduler || !unofficial.globallyEnabled()) return;
        try { runDue(); } catch (RuntimeException e) { log.warn("Manual-style pass failed: {}", e.getMessage()); }
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }

    /** One pass: at most one message per running broadcast. Returns how many went out. Public for tests. */
    public synchronized int runDue() {
        int sent = 0;
        HumanPolicy pol = policies.manual();
        for (var b : jdbc.sql("""
                SELECT id, tenant_id, created_by, message, variants::text AS variants, manual_next_at, manual_seq, manual_since_break, manual_break_after, manual_last_hash
                FROM commerce.broadcasts WHERE status = 'SENDING' AND """ + " " + com.platform.shared.TenantState.running("tenant_id")
                + " AND EXISTS (SELECT 1 FROM commerce.broadcast_recipients r WHERE r.broadcast_id = broadcasts.id AND r.route = 'MANUAL_STYLE' AND r.status IN ('PENDING', 'SENDING')) ORDER BY created_at LIMIT 20").query().listOfRows()) {
            UUID id = (UUID) b.get("id"), tenantId = (UUID) b.get("tenant_id");
            var next = nextPending(id);
            if (next == null) { done(id); continue; }
            String channel = (String) next.get("channel");
            // 1) too many STOP replies: pause for the owner to look
            var stats = jdbc.sql("SELECT count(*) FILTER (WHERE opted_out_at IS NOT NULL) AS stops, count(*) FILTER (WHERE status = 'SENT') AS sent FROM commerce.broadcast_recipients WHERE broadcast_id = :b").param("b", id).query().listOfRows().get(0);
            long stops = num(stats.get("stops")), sentSoFar = num(stats.get("sent"));
            if (stops >= 3 && stops * 100 >= sentSoFar * 3) { pause(id, "OPT_OUTS"); continue; }
            // 2) the session must be ACTIVE and the feature on: a blocked / expired / disabled account is never tried again by itself
            if (!unofficial.globallyEnabled()) { pause(id, "MANUAL_STYLE_OFF"); continue; }
            String st = unofficial.status(tenantId, channel);
            if (!"ACTIVE".equals(st)) { pause(id, "NEEDS_LOGIN".equals(st) ? "SESSION_EXPIRED" : "BLOCKED".equals(st) ? "ACCOUNT_BLOCK_SIGNAL" : "MANUAL_STYLE_OFF"); continue; }
            // 3) active hours in the shop's time
            if (!pol.inActiveWindow(segments.localNow(tenantId))) { segments.setReason(id, "MS_ACTIVE_HOURS"); continue; }
            // 4) the random gap / break after the last message
            if (b.get("manual_next_at") != null && ((Timestamp) b.get("manual_next_at")).toInstant().isAfter(Instant.now())) continue;
            // 5) caps: per hour, and per day on the warm-up ramp
            if (!pol.hourlyAllows(manualSent(tenantId, channel, "1 hour"))) { segments.setReason(id, "MS_HOURLY_CAP"); continue; }
            if (!pol.dailyAllows(unofficial.warmupDay(tenantId, channel), unofficial.sentToday(tenantId, channel))) { segments.setReason(id, "MS_DAILY_CAP"); continue; }
            segments.clearReason(id, "MS_ACTIVE_HOURS", "MS_HOURLY_CAP", "MS_DAILY_CAP");
            // 6) the sidecar must answer before every send; when it does not, wait for the owner
            var sender = unofficial.transport(channel);
            if (sender == null || !sender.healthy()) { pause(id, "BRIDGE_UNREACHABLE"); continue; }
            List<String> versions = new ArrayList<>();
            versions.add((String) b.get("message"));
            try { if (b.get("variants") != null) for (var v : JSON.readTree(b.get("variants").toString())) versions.add(v.asText()); } catch (Exception ignored) { /* the main wording only */ }
            String shop = segments.shopName(tenantId);
            for (int look = 0; look < 50; look++) {   // a contact skipped at the last moment costs no gap
                var rcp = nextPending(id);
                if (rcp == null) break;
                UUID rid = (UUID) rcp.get("id");
                if (jdbc.sql("UPDATE commerce.broadcast_recipients SET status = 'SENDING', sent_at = now(), error = NULL WHERE id = :i AND status = 'PENDING'").param("i", rid).update() == 0) continue;
                String ext = (String) rcp.get("external_id"), name = (String) rcp.get("name");
                String skip = skipReason(tenantId, channel, ext, pol);
                if (skip != null) { segments.finish(id, rid, "SKIPPED", skip, 0, false); continue; }
                // the wording: rotate the versions in turn, first name, tiny safe variations, one link at most; the opt-out line goes last
                int seq = (int) num(b.get("manual_seq"));
                String body = SegmentService.personalize(HumanPolicy.rotate(versions.get(0), versions.subList(1, versions.size()), seq), name, shop, Gender.of((String) rcp.get("gender")), parseVars(rcp.get("vars")), rid.getMostSignificantBits());
                body = pol.vary(HumanPolicy.stripRepeatedLinks(body), rnd, (String) b.get("manual_last_hash")) + SegmentService.OPT_OUT_LINE;
                if (pol.contentProblem(body) != null) { segments.finish(id, rid, "SKIPPED", "CONTENT_GUARD", 0, false); continue; }
                String ref = "bc-" + id + "-" + rid;
                if (!billing.charge(tenantId, ref, "Broadcast message")) {
                    segments.requeue(rid);
                    jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'NO_CREDIT' WHERE id = :i").param("i", id).update();
                    break;
                }
                long price = billing.priceMinor();
                jdbc.sql("UPDATE commerce.broadcast_recipients SET cost_minor = :c WHERE id = :i").param("c", price).param("i", rid).update();
                // (e) the bridge marks the thread seen and "types" for about this long (40 ms per character plus 2-6 s) before it sends
                int typingMs = pol.typingMillis(body.length(), rnd, 0);
                String target = name != null && USERNAME.matcher(name.trim()).matches() ? name.trim() : ext;
                OutOfWindowSender.Result res = sender.send(tenantId, channel, target, body, typingMs);
                boolean ok = res.ok();
                if (!ok) billing.refund(tenantId, ref, "Broadcast message not delivered");   // never charge for a message that was not sent
                switch (res.kind()) {
                    case SENT -> { segments.finish(id, rid, "SENT", null, price, false); sent++; unofficial.recordSent(tenantId, channel); }
                    case BLOCKED -> segments.finish(id, rid, "FAILED", "ACCOUNT_BLOCK_SIGNAL", 0, false);
                    case SESSION_EXPIRED -> segments.requeue(rid);   // not delivered and not the contact's fault: it stays in the queue for when the owner logs in again
                    case NOT_FOUND -> segments.finish(id, rid, "FAILED", "NOT_FOUND", 0, false);
                    default -> segments.finish(id, rid, "FAILED", res.detail() != null && res.detail().length() <= 40 ? res.detail() : "SEND_FAILED", 0, false);
                }
                if (res.kind() == OutOfWindowSender.Kind.BLOCKED) { blockStop(tenantId, id, (UUID) b.get("created_by"), channel, res.detail()); break; }
                if (res.kind() == OutOfWindowSender.Kind.SESSION_EXPIRED) { expiredStop(tenantId, id, (UUID) b.get("created_by"), channel, res.detail()); break; }
                // the gap before the next one, and the longer break after every 8-15 messages
                long gapMs = pol.gapMillis(rnd);
                int since = (int) num(b.get("manual_since_break")) + 1;
                int after = b.get("manual_break_after") == null ? pol.nextBreakAfter(rnd) : (int) num(b.get("manual_break_after"));
                if (ok && since >= after) { gapMs = Math.max(gapMs, pol.breakMillis(rnd)); since = 0; after = pol.nextBreakAfter(rnd); }
                jdbc.sql("UPDATE commerce.broadcasts SET manual_next_at = now() + make_interval(secs => :g), manual_seq = manual_seq + 1, manual_since_break = :s, manual_break_after = :a, manual_last_hash = :h WHERE id = :i")
                        .param("g", gapMs / 1000.0).param("s", since).param("a", after).param("h", HumanPolicy.hash(body)).param("i", id).update();
                int streak = jdbc.sql("UPDATE commerce.broadcasts SET consecutive_failures = CASE WHEN :ok THEN 0 ELSE consecutive_failures + 1 END WHERE id = :i RETURNING consecutive_failures").param("ok", ok).param("i", id).query(Integer.class).single();
                if (streak >= 5) pause(id, "FAILURES");   // five failures in a row: something is wrong with the account
                else if ("BRIDGE_UNREACHABLE".equals(res.detail())) pause(id, "BRIDGE_UNREACHABLE");
                break;
            }
            done(id);
        }
        return sent;
    }

    private Map<String, Object> nextPending(UUID broadcastId) {
        return jdbc.sql("SELECT id, channel, external_id, name, gender, vars::text AS vars FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'PENDING' AND route = 'MANUAL_STYLE' ORDER BY id LIMIT 1")
                .param("b", broadcastId).query().listOfRows().stream().findFirst().orElse(null);
    }

    private void done(UUID id) {
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'DONE', pause_reason = NULL WHERE id = :i AND status = 'SENDING' AND NOT EXISTS (SELECT 1 FROM commerce.broadcast_recipients WHERE broadcast_id = :i AND status IN ('PENDING', 'SENDING'))").param("i", id).update();
    }

    private void pause(UUID id, String reason) {
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = :r WHERE id = :i AND status = 'SENDING'").param("r", reason).param("i", id).update();
    }

    long manualSent(UUID tenantId, String channel, String interval) {
        return jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients r JOIN commerce.broadcasts b ON b.id = r.broadcast_id WHERE b.tenant_id = :t AND r.channel = :c AND r.route = 'MANUAL_STYLE' AND r.status = 'SENT' AND r.sent_at > now() - CAST(:i AS interval)")
                .param("t", tenantId).param("c", channel).param("i", interval).query(Long.class).single();
    }

    /** Why this contact must not get a hand-style message right now (checked again at the moment of sending), or null. */
    private String skipReason(UUID tenantId, String channel, String ext, HumanPolicy pol) {
        var c = jdbc.sql("""
                SELECT broadcast_opt_out_at IS NOT NULL AS stopped, (last_customer_at > now() - interval '23 hours' OR window_until > now()) AS open, last_customer_at,
                       (SELECT count(*) FROM (SELECT 1 FROM chat.messages m WHERE m.conversation_id = cv.id AND m.role = 'user' LIMIT 3) x) AS cust_msgs,
                       (SELECT count(*) FROM (SELECT 1 FROM chat.messages m WHERE m.conversation_id = cv.id LIMIT 3) x) AS all_msgs,
                       EXISTS (SELECT 1 FROM commerce.broadcast_recipients br JOIN commerce.broadcasts bb ON bb.id = br.broadcast_id
                                WHERE bb.tenant_id = :t AND br.route = 'MANUAL_STYLE' AND br.channel = cv.channel AND br.external_id = cv.external_id AND br.status = 'SENT'
                                  AND br.sent_at > now() - make_interval(days => :d)) AS recent_manual
                FROM chat.conversations cv WHERE tenant_id = :t AND channel = :c AND external_id = :e
                """).param("t", tenantId).param("c", channel).param("e", ext).param("d", Math.max(1, pol.profile().sameContactDays())).query().listOfRows().stream().findFirst().orElse(null);
        if (c == null) return "NO_CONVERSATION";
        if (Boolean.TRUE.equals(c.get("stopped"))) return "OPTED_OUT";
        if (Boolean.TRUE.equals(c.get("open"))) return "WINDOW_OPENED";   // they wrote again meanwhile: not a cold message any more, leave it to the inbox
        Instant last = c.get("last_customer_at") instanceof Timestamp t ? t.toInstant() : null;
        String g = SegmentService.manualGuard(pol.profile(), last, num(c.get("all_msgs")), num(c.get("cust_msgs")), Boolean.TRUE.equals(c.get("recent_manual")), Instant.now());
        if (g != null) return g;
        return segments.ownerActive(tenantId, channel, ext, pol.profile().ownerReplyMinutes()) ? "OWNER_ACTIVE" : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseVars(Object o) {
        try { return o == null ? Map.of() : JSON.readValue(o.toString(), Map.class); } catch (Exception e) { return Map.of(); }
    }

    // ---- stop signals --------------------------------------------------------------------------------------------------------------

    /** The first block signal (action block, feedback required, challenge, checkpoint, rate limit): block the session, pause, tell the owner. No retries. */
    private void blockStop(UUID tenantId, UUID broadcastId, UUID owner, String channel, String detail) {
        unofficial.markBlocked(tenantId, channel, detail == null ? "BLOCK_SIGNAL" : detail);
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'ACCOUNT_BLOCK_SIGNAL' WHERE tenant_id = :t AND status = 'SENDING' AND EXISTS (SELECT 1 FROM commerce.broadcast_recipients r WHERE r.broadcast_id = broadcasts.id AND r.route = 'MANUAL_STYLE' AND r.channel = :c AND r.status = 'PENDING')")
                .param("t", tenantId).param("c", channel).update();
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'ACCOUNT_BLOCK_SIGNAL' WHERE id = :i AND status = 'SENDING'").param("i", broadcastId).update();
        notifyOwner(tenantId, owner, "BROADCAST_ACCOUNT_BLOCK", "تم إيقاف الإرسال اليدوي / Hand-style sending stopped",
                "ظهرت إشارة حظر من الحساب فتم إيقاف الإرسال لمدة " + unofficial.blockHours() + " ساعة لحمايته. لا تحاول الإرسال من الحساب الآن. / The account showed a block signal, so sending is stopped for " + unofficial.blockHours() + " hours to protect it.");
    }

    private void expiredStop(UUID tenantId, UUID broadcastId, UUID owner, String channel, String detail) {
        unofficial.markNeedsLogin(tenantId, channel, detail == null ? "SESSION_EXPIRED" : detail);
        jdbc.sql("UPDATE commerce.broadcasts SET status = 'PAUSED', pause_reason = 'SESSION_EXPIRED' WHERE id = :i AND status = 'SENDING'").param("i", broadcastId).update();
        notifyOwner(tenantId, owner, "BROADCAST_SESSION_EXPIRED", "سجّل دخول الحساب من جديد / Log the account in again",
                "انتهت جلسة الحساب فتوقف الإرسال اليدوي. اربط الحساب من جديد لإكمال الإرسال. / The account session expired, so hand-style sending paused. Connect the account again to continue.");
    }

    private void notifyOwner(UUID tenantId, UUID owner, String type, String title, String body) {
        if (owner == null) return;
        try {
            notifications.notify(owner, tenantId, type, title, body, "{\"type\":\"" + type + "\"}", false);
            notifications.push(owner, tenantId, title, body, "{\"type\":\"" + type + "\"}");
            notifications.dispatchSoon();
        } catch (RuntimeException e) {
            log.warn("Could not notify the owner about a stopped broadcast: {}", e.getMessage());
        }
    }
}
