package com.platform.chat.unofficial;

import com.platform.audit.AuditService;
import com.platform.chat.TokenCrypto;
import com.platform.shared.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The shop's unofficial Messenger / Instagram sessions: connect (no approval step any more), wipe, disable / enable, and the counters the manual-style pacing needs.
 * On by default ({@code app.meta.unofficial.enabled} stays as an operator kill switch). The login details are stored encrypted with the same {@link TokenCrypto} as the channel tokens, never logged and never
 * returned by any API: only the account name and the status are ever shown.
 *
 * Honest limit: verified only against a fake {@link OutOfWindowSender}; no real Instagram account was ever used.
 */
@Service
public class UnofficialMetaService {
    private static final Logger log = LoggerFactory.getLogger(UnofficialMetaService.class);
    public static final List<String> PLATFORMS = List.of("INSTAGRAM", "FACEBOOK");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcClient jdbc;
    private final TokenCrypto crypto;
    private final AuditService audit;
    private final List<OutOfWindowSender> senders;
    private final HumanPolicies policies;
    private volatile boolean enabled;
    private final int blockHours;

    public UnofficialMetaService(JdbcClient jdbc, TokenCrypto crypto, AuditService audit, List<OutOfWindowSender> senders, HumanPolicies policies,
                                 @Value("${app.meta.unofficial.enabled:true}") boolean enabled, @Value("${app.meta.unofficial.block-hours:48}") int blockHours) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.senders = senders;
        this.policies = policies;
        this.enabled = enabled;
        this.blockHours = Math.max(1, blockHours);
    }

    public boolean globallyEnabled() { return enabled; }

    public HumanPolicy policy() { return policies.manual(); }

    /** The stricter profile for cold outreach (prospects). */
    public HumanPolicy coldPolicy() { return policies.cold(); }

    public int blockHours() { return blockHours; }

    OutOfWindowSender sender(String platform) {
        for (OutOfWindowSender s : senders) if (s.handles(platform)) return s;
        return null;
    }

    public OutOfWindowSender transport(String platform) { return sender(platform); }

    public boolean supported(String platform) { var s = sender(platform); return s != null && s.supported(); }

    private static String norm(String platform) {
        String p = platform == null ? "" : platform.trim().toUpperCase();
        if ("MESSENGER".equals(p)) p = "FACEBOOK";
        if (!PLATFORMS.contains(p)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown platform");
        return p;
    }

    public static String platformOf(String path) { return norm(path); }

    public ZoneId zone(UUID tenantId) {
        try { return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single()); }
        catch (RuntimeException e) { return ZoneId.of("Africa/Cairo"); }
    }

    private Map<String, Object> row(UUID tenantId, String platform) {
        return jdbc.sql("SELECT account_name, status, risk_accepted_at, warmup_started_at, sent_today, sent_today_date, last_sent_at, blocked_until, last_error FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = :p")
                .param("t", tenantId).param("p", platform).query().listOfRows().stream().findFirst().orElse(null);
    }

    /** The route is usable right now: the feature is on, the platform has a real transport and the shop's session is ACTIVE. */
    public boolean reachable(UUID tenantId, String platform) {
        if (!enabled || !supported(platform)) return false;
        var r = row(tenantId, platform);
        return r != null && "ACTIVE".equals(r.get("status"));
    }

    public String status(UUID tenantId, String platform) { var r = row(tenantId, platform); return r == null ? null : (String) r.get("status"); }

    public int warmupDay(UUID tenantId, String platform) {
        var r = row(tenantId, platform);
        if (r == null || !(r.get("warmup_started_at") instanceof Timestamp t)) return 1;
        ZoneId z = zone(tenantId);
        return HumanPolicy.warmupDay(t.toInstant().atZone(z).toLocalDate(), LocalDate.now(z));
    }

    public int sentToday(UUID tenantId, String platform) {
        var r = row(tenantId, platform);
        if (r == null || r.get("sent_today_date") == null) return 0;
        LocalDate d = r.get("sent_today_date") instanceof java.sql.Date sd ? sd.toLocalDate() : LocalDate.parse(r.get("sent_today_date").toString());
        return d.equals(LocalDate.now(zone(tenantId))) ? ((Number) r.get("sent_today")).intValue() : 0;
    }

    public int dailyCapToday(UUID tenantId, String platform) { return policy().dailyCap(warmupDay(tenantId, platform)); }

    /** A manual-style message went out: today's counter and the last-sent time. */
    public void recordSent(UUID tenantId, String platform) {
        LocalDate today = LocalDate.now(zone(tenantId));
        jdbc.sql("UPDATE chat.unofficial_sessions SET sent_today = CASE WHEN sent_today_date = :d THEN sent_today + 1 ELSE 1 END, sent_today_date = :d, last_sent_at = now(), updated_at = now() WHERE tenant_id = :t AND platform = :p")
                .param("d", java.sql.Date.valueOf(today)).param("t", tenantId).param("p", platform).update();
    }

    /** The first block signal: the session stops being used for {@code block-hours} (48 by default) and only the owner can bring it back. */
    public void markBlocked(UUID tenantId, String platform, String why) {
        jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'BLOCKED', blocked_until = now() + make_interval(hours => :h), last_error = :e, updated_at = now() WHERE tenant_id = :t AND platform = :p")
                .param("h", blockHours).param("e", clip(why)).param("t", tenantId).param("p", platform).update();
    }

    public void markNeedsLogin(UUID tenantId, String platform, String why) {
        jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'NEEDS_LOGIN', last_error = :e, updated_at = now() WHERE tenant_id = :t AND platform = :p")
                .param("e", clip(why)).param("t", tenantId).param("p", platform).update();
    }

    private static String clip(String s) { return s == null ? null : s.length() > 190 ? s.substring(0, 190) : s; }

    // ---- the owner's side ----------------------------------------------------------------------------------------------------------

    /** Per platform: is it supported, is the feature on, is a session connected, its state and today's caps. Never any credential. */
    public Map<String, Object> overview(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (String p : PLATFORMS) {
            var r = row(tenantId, p);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", p);
            m.put("supported", supported(p));
            m.put("enabled", enabled);
            m.put("connected", r != null);
            m.put("accountName", r == null ? null : r.get("account_name"));
            m.put("account", r == null ? null : r.get("account_name"));
            m.put("status", r == null ? "NONE" : r.get("status"));
            int day = warmupDay(tenantId, p);
            m.put("warmupDay", r == null ? null : day);
            m.put("sentToday", r == null ? 0 : sentToday(tenantId, p));
            m.put("dailyCap", policy().dailyCap(day));
            m.put("blockedUntil", r == null || !(r.get("blocked_until") instanceof Timestamp t) ? null : t.toInstant());
            m.put("lastError", r == null ? null : r.get("last_error"));
            platforms.put(p, m);
        }
        out.put("platforms", platforms);
        var pr = policy().profile();
        Map<String, Object> pol = new LinkedHashMap<>();
        pol.put("gapMinSeconds", pr.gapMinSeconds());
        pol.put("gapMaxSeconds", pr.gapMaxSeconds());
        pol.put("jitterMaxSeconds", pr.jitterMaxSeconds());
        pol.put("breakEveryMin", pr.breakEveryMin());
        pol.put("breakEveryMax", pr.breakEveryMax());
        pol.put("breakMinSeconds", pr.breakMinSeconds());
        pol.put("breakMaxSeconds", pr.breakMaxSeconds());
        pol.put("hourlyCap", pr.hourlyCap());
        pol.put("maxDaily", pr.maxDaily());
        List<Integer> ramp = new ArrayList<>();
        for (int c : pr.warmupCaps()) ramp.add(c);
        pol.put("warmupCaps", ramp);
        pol.put("activeFromHour", pr.activeFromHour());
        pol.put("activeToHour", pr.activeToHour());
        pol.put("minVariants", pr.minVariants());
        pol.put("blockHours", blockHours);
        out.put("policy", pol);
        return out;
    }

    /** Instagram login. {@code acceptedRisk} is accepted from old clients and ignored: there is no approval step any more. */
    public Map<String, Object> connect(UUID tenantId, UUID actor, String platformIn, String username, String password, String totpSecret, boolean acceptedRisk) {
        String platform = norm(platformIn);
        var s = usable(platform);
        String u = username == null ? "" : username.trim().replaceFirst("^@", "");
        if (u.length() < 2 || u.length() > 60 || password == null || password.length() < 4 || password.length() > 200) throw BusinessException.badRequest("VALIDATION_ERROR", "Enter the account name and password");
        var res = s.login(tenantId, platform, u, password, totpSecret == null || totpSecret.isBlank() ? null : totpSecret.trim());
        failIfNot(res);
        String blob;
        try { blob = crypto.encrypt(JSON.writeValueAsString(Map.of("u", u, "p", password, "t", totpSecret == null ? "" : totpSecret.trim()))); }
        catch (Exception e) { throw new IllegalStateException("Could not store the login", e); }
        return store(tenantId, actor, platform, res.accountName() == null ? u : res.accountName(), blob);
    }

    /** Messenger login: the pasted cookie string and the Page id (default: the Page connected in the channels). Stored encrypted as {@code {c, pg}} in the same column. */
    public Map<String, Object> connectCookies(UUID tenantId, UUID actor, String platformIn, String cookies, String pageId) {
        String platform = norm(platformIn);
        var s = usable(platform);
        String c = cookies == null ? "" : cookies.trim();
        if (c.length() < 20 || c.length() > 8000) throw BusinessException.badRequest("VALIDATION_ERROR", "Paste the account cookies");
        String pg = pageId == null ? "" : pageId.trim();
        if (pg.isEmpty()) pg = jdbc.sql("SELECT external_id FROM chat.channels WHERE tenant_id = :t AND platform = :p ORDER BY (status = 'ACTIVE') DESC, created_at LIMIT 1").param("t", tenantId).param("p", platform).query(String.class).optional().orElse("");
        if (!pg.matches("\\d{5,30}")) throw BusinessException.badRequest("VALIDATION_ERROR", "Enter the Page id");
        var res = s.loginWithCookies(tenantId, platform, c, pg);
        failIfNot(res);
        String blob;
        try { blob = crypto.encrypt(JSON.writeValueAsString(Map.of("c", c, "pg", pg))); }
        catch (Exception e) { throw new IllegalStateException("Could not store the login", e); }
        return store(tenantId, actor, platform, res.accountName() == null ? pg : res.accountName(), blob);
    }

    private OutOfWindowSender usable(String platform) {
        if (!enabled) throw BusinessException.conflict("UNOFFICIAL_META_OFF", "This feature is not available on this server");
        var s = sender(platform);
        if (s == null || !s.supported()) throw BusinessException.conflict("UNSUPPORTED_PLATFORM", "Not available for this platform yet");
        return s;
    }

    private static void failIfNot(OutOfWindowSender.LoginResult res) {
        if (res.ok()) return;
        String code = res.error() == null ? "FAILED" : res.error();
        throw switch (code) {
            case "CHALLENGE" -> BusinessException.conflict("CHALLENGE_REQUIRED", "The account asked for an extra check (code or approval). Approve the login on your phone or add the two-factor secret, then try again");
            case "BAD_CREDENTIALS" -> BusinessException.badRequest("BAD_CREDENTIALS", "The account details were not accepted");
            case "UNREACHABLE" -> BusinessException.conflict("BRIDGE_UNREACHABLE", "The sending service is not reachable right now");
            case "UNSUPPORTED" -> BusinessException.conflict("UNSUPPORTED_PLATFORM", "Not available for this platform yet");
            default -> BusinessException.conflict("LOGIN_FAILED", "Could not log in");
        };
    }

    private Map<String, Object> store(UUID tenantId, UUID actor, String platform, String account, String blob) {
        jdbc.sql("""
                INSERT INTO chat.unofficial_sessions (tenant_id, platform, account_name, credentials_enc, status, warmup_started_at, blocked_until, last_error)
                VALUES (:t, :p, :a, :c, 'ACTIVE', now(), NULL, NULL)
                ON CONFLICT (tenant_id, platform) DO UPDATE SET account_name = :a, credentials_enc = :c, status = 'ACTIVE',
                    blocked_until = NULL, last_error = NULL, updated_at = now()
                """).param("t", tenantId).param("p", platform).param("a", account).param("c", blob).update();
        audit.record(actor, tenantId, "UNOFFICIAL_META_CONNECTED", "unofficial_session", tenantId, "{\"platform\":\"" + platform + "\"}");
        return one(tenantId, platform);
    }

    private Map<String, Object> one(UUID tenantId, String platform) {
        @SuppressWarnings("unchecked") Map<String, Object> all = (Map<String, Object>) ((Map<String, Object>) overview(tenantId).get("platforms")).get(platform);
        return all;
    }

    /** Deletes the stored login and asks the transport to drop its session. */
    public Map<String, Object> wipe(UUID tenantId, UUID actor, String platformIn) {
        String platform = norm(platformIn);
        var s = sender(platform);
        try { if (s != null) s.logout(tenantId, platform); } catch (RuntimeException e) { log.warn("Could not ask the sending service to drop a session: {}", e.getClass().getSimpleName()); }
        jdbc.sql("DELETE FROM chat.unofficial_sessions WHERE tenant_id = :t AND platform = :p").param("t", tenantId).param("p", platform).update();
        audit.record(actor, tenantId, "UNOFFICIAL_META_WIPED", "unofficial_session", tenantId, "{\"platform\":\"" + platform + "\"}");
        return one(tenantId, platform);
    }

    public Map<String, Object> disable(UUID tenantId, UUID actor, String platformIn) {
        String platform = norm(platformIn);
        if (jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'DISABLED', updated_at = now() WHERE tenant_id = :t AND platform = :p AND status <> 'BLOCKED'").param("t", tenantId).param("p", platform).update() == 0
                && row(tenantId, platform) == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not connected");
        audit.record(actor, tenantId, "UNOFFICIAL_META_DISABLED", "unofficial_session", tenantId, "{\"platform\":\"" + platform + "\"}");
        return one(tenantId, platform);
    }

    /** Brings a disabled session back (or a blocked one, only after its {@code block-hours} are over). A session that needs a new login must be connected again. */
    public Map<String, Object> enable(UUID tenantId, UUID actor, String platformIn) {
        String platform = norm(platformIn);
        var r = row(tenantId, platform);
        if (r == null) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not connected");
        if (!enabled) throw BusinessException.conflict("UNOFFICIAL_META_OFF", "This feature is not available on this server");
        String st = (String) r.get("status");
        if ("NEEDS_LOGIN".equals(st)) throw BusinessException.conflict("NEEDS_LOGIN", "Log in again first");
        if ("BLOCKED".equals(st) && r.get("blocked_until") instanceof Timestamp t && t.toInstant().isAfter(Instant.now()))
            throw BusinessException.conflict("STILL_BLOCKED", "The account stays paused until " + t.toInstant() + " to protect it");
        var s = sender(platform);
        var live = s == null ? null : s.status(tenantId, platform);
        if (live == null || !live.active()) {
            markNeedsLogin(tenantId, platform, "SESSION_EXPIRED");
            throw BusinessException.conflict("NEEDS_LOGIN", "Log in again first");
        }
        jdbc.sql("UPDATE chat.unofficial_sessions SET status = 'ACTIVE', blocked_until = NULL, last_error = NULL, updated_at = now() WHERE tenant_id = :t AND platform = :p").param("t", tenantId).param("p", platform).update();
        audit.record(actor, tenantId, "UNOFFICIAL_META_ENABLED", "unofficial_session", tenantId, "{\"platform\":\"" + platform + "\"}");
        return one(tenantId, platform);
    }
}
