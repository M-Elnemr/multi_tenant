package com.platform.analytics;

import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Records anonymous page views of the public websites and lets platform admins read them. Never linked to an account. */
@Service
public class VisitService {
    /** Areas of the site that belong to signed-in people; they are never recorded. */
    private static final List<String> PRIVATE = List.of("/dashboard", "/admin", "/account", "/portal", "/orders", "/checkout", "/login");

    public record View(UUID id, UUID visitorId, UUID sessionId, String host, UUID tenantId, String path, String query, String referrer, String ip,
                       String country, String city, String userAgent, String language, String timezone, Integer screenW, Integer screenH) {}

    private final JdbcClient jdbc;

    public VisitService(JdbcClient jdbc) { this.jdbc = jdbc; }

    static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }

    static boolean isPrivate(String path) {
        return PRIVATE.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /** Returns false when the view was ignored (bot, private area, malformed path). */
    public boolean record(View v) {
        String path = cut(v.path(), 500);
        if (path == null || !path.startsWith("/") || isPrivate(path)) return false;
        UserAgentInfo ua = UserAgentInfo.parse(v.userAgent());
        if (ua.bot()) return false;
        jdbc.sql("""
                INSERT INTO analytics.visits (id, visitor_id, session_id, tenant_id, host, path, query, referrer, ip, country, city, user_agent,
                                              browser, os, device_type, language, timezone, screen_w, screen_h)
                VALUES (:id, :vid, :sid, :tid, :host, :path, :q, :ref, :ip, :country, :city, :ua, :browser, :os, :device, :lang, :tz, :sw, :sh)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", v.id()).param("vid", v.visitorId()).param("sid", v.sessionId()).param("tid", v.tenantId())
                .param("host", cut(v.host(), 255)).param("path", path).param("q", cut(v.query(), 500)).param("ref", cut(v.referrer(), 500))
                .param("ip", cut(v.ip(), 64)).param("country", cut(v.country(), 8)).param("city", cut(v.city(), 100)).param("ua", cut(v.userAgent(), 500))
                .param("browser", ua.browser()).param("os", ua.os()).param("device", ua.deviceType())
                .param("lang", cut(v.language(), 20)).param("tz", cut(v.timezone(), 64)).param("sw", v.screenW()).param("sh", v.screenH()).update();
        return true;
    }

    /** Time spent on a page, reported when the visitor leaves it. Only the browser that created the view can set it. */
    public void leave(UUID id, UUID visitorId, long durationMs) {
        int ms = (int) Math.max(0, Math.min(durationMs, 4L * 60 * 60 * 1000));
        jdbc.sql("UPDATE analytics.visits SET duration_ms = :d WHERE id = :id AND visitor_id = :v").param("d", ms).param("id", id).param("v", visitorId).update();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOld() {
        jdbc.sql("DELETE FROM analytics.visits WHERE viewed_at < now() - interval '90 days'").update();
    }

    // ---- admin reads --------------------------------------------------------------------------------------------

    /** One row per visitor in the period: when they came, how much they browsed, and where from. */
    public Map<String, Object> visitors(String from, String to, UUID tenantId, String path, String country, String device, Page page) {
        String where = " WHERE viewed_at >= COALESCE(CAST(:from AS timestamptz), now() - interval '7 days') AND viewed_at < COALESCE(CAST(:to AS timestamptz), now() + interval '1 day')"
                + " AND (CAST(:t AS uuid) IS NULL OR tenant_id = CAST(:t AS uuid)) AND (CAST(:p AS varchar) IS NULL OR path ILIKE '%' || CAST(:p AS varchar) || '%')"
                + " AND (CAST(:c AS varchar) IS NULL OR country = CAST(:c AS varchar)) AND (CAST(:d AS varchar) IS NULL OR device_type = CAST(:d AS varchar))";
        long total = bind(jdbc.sql("SELECT count(DISTINCT visitor_id) FROM analytics.visits" + where), from, to, tenantId, path, country, device).query(Long.class).single();
        var rows = bind(jdbc.sql("""
                SELECT visitor_id, min(viewed_at) AS first_seen, max(viewed_at) AS last_seen, count(*) AS page_views, count(DISTINCT session_id) AS sessions,
                       coalesce(sum(duration_ms), 0) AS total_ms,
                       (array_agg(country ORDER BY viewed_at DESC))[1] AS country, (array_agg(city ORDER BY viewed_at DESC))[1] AS city,
                       (array_agg(ip ORDER BY viewed_at DESC))[1] AS ip, (array_agg(browser ORDER BY viewed_at DESC))[1] AS browser,
                       (array_agg(os ORDER BY viewed_at DESC))[1] AS os, (array_agg(device_type ORDER BY viewed_at DESC))[1] AS device_type,
                       (array_agg(language ORDER BY viewed_at DESC))[1] AS language, (array_agg(host ORDER BY viewed_at DESC))[1] AS host,
                       (array_agg(tenant_id ORDER BY viewed_at DESC))[1] AS tenant_id,
                       (array_agg(referrer ORDER BY viewed_at ASC))[1] AS first_referrer
                FROM analytics.visits""" + where + " GROUP BY visitor_id ORDER BY max(viewed_at) DESC LIMIT :lim OFFSET :off"), from, to, tenantId, path, country, device)
                .param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(rows.stream().map(Rows::camel).toList(), total);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec s, String from, String to, UUID tenantId, String path, String country, String device) {
        return s.param("from", from).param("to", to).param("t", tenantId).param("p", path).param("c", country).param("d", device);
    }

    /** Every page one visitor opened, newest first, with the time spent on each. */
    public Map<String, Object> timeline(UUID visitorId) {
        var rows = jdbc.sql("""
                SELECT v.id, v.session_id, v.viewed_at, v.host, v.path, v.query, v.referrer, v.duration_ms, v.ip, v.country, v.city, v.browser, v.os,
                       v.device_type, v.language, v.timezone, v.screen_w, v.screen_h, v.user_agent, t.name AS tenant_name
                FROM analytics.visits v LEFT JOIN core.tenants t ON t.id = v.tenant_id
                WHERE v.visitor_id = :v ORDER BY v.viewed_at DESC LIMIT 500
                """).param("v", visitorId).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visitorId", visitorId);
        out.put("views", rows.stream().map(Rows::camel).toList());
        return out;
    }

    public Map<String, Object> summary(String from, String to) {
        String where = " WHERE viewed_at >= COALESCE(CAST(:from AS timestamptz), now() - interval '7 days') AND viewed_at < COALESCE(CAST(:to AS timestamptz), now() + interval '1 day')";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", Rows.camel(jdbc.sql("SELECT count(DISTINCT visitor_id) AS visitors, count(DISTINCT session_id) AS sessions, count(*) AS page_views, coalesce(avg(duration_ms), 0)::int AS avg_ms FROM analytics.visits" + where)
                .param("from", from).param("to", to).query().singleRow()));
        for (String[] g : new String[][] {{"topPages", "host || path"}, {"countries", "country"}, {"devices", "device_type"}, {"browsers", "browser"}, {"referrers", "referrer"}}) {
            List<Map<String, Object>> rows = new ArrayList<>();
            jdbc.sql("SELECT " + g[1] + " AS name, count(*) AS views, count(DISTINCT visitor_id) AS visitors FROM analytics.visits" + where
                            + " AND " + g[1] + " IS NOT NULL AND " + g[1] + " <> '' GROUP BY 1 ORDER BY 2 DESC LIMIT 10")
                    .param("from", from).param("to", to).query().listOfRows().forEach(r -> rows.add(Rows.camel(r)));
            out.put(g[0], rows);
        }
        out.put("perDay", jdbc.sql("SELECT date_trunc('day', viewed_at)::date AS day, count(*) AS views, count(DISTINCT visitor_id) AS visitors FROM analytics.visits" + where + " GROUP BY 1 ORDER BY 1")
                .param("from", from).param("to", to).query().listOfRows().stream().map(Rows::camel).toList());
        return out;
    }
}
