package com.platform.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the AI seller is doing, for the platform admin: replies per day and model with their time, failures and switches to the backup model, which checks had to correct
 * replies, the shops that need the most corrections, chats handed to shop owners, the scheduled quality scores, and the state of the backups.
 */
@RestController
@RequestMapping("/api/v1/platform/ai-health")
@PreAuthorize("hasAuthority('platform.chat.teach')")
public class AiHealthController {
    private final JdbcClient jdbc;

    public AiHealthController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> health(@RequestParam(defaultValue = "7") int days) {
        int d = Math.max(1, Math.min(60, days));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("byDay", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT to_char(created_at AT TIME ZONE 'Africa/Cairo', 'YYYY-MM-DD') AS day, coalesce(model, '-') AS model,
                       count(*) FILTER (WHERE kind = 'REPLY') AS replies,
                       round(avg(ms) FILTER (WHERE kind = 'REPLY')) AS avg_ms,
                       round(percentile_cont(0.9) WITHIN GROUP (ORDER BY ms) FILTER (WHERE kind = 'REPLY')) AS p90_ms,
                       count(*) FILTER (WHERE kind = 'ERROR') AS errors, count(*) FILTER (WHERE kind = 'FALLBACK') AS fallbacks,
                       count(*) FILTER (WHERE kind LIKE 'CHECK:%') AS corrections
                FROM chat.ai_events WHERE created_at > now() - make_interval(days => :d)
                GROUP BY 1, 2 ORDER BY 1 DESC, 2
                """).param("d", d).query().listOfRows()));
        out.put("checks", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT substring(kind FROM 7) AS check_name, coalesce(model, '-') AS model, count(*) AS times
                FROM chat.ai_events WHERE kind LIKE 'CHECK:%' AND created_at > now() - make_interval(days => :d)
                GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 40
                """).param("d", d).query().listOfRows()));
        out.put("shops", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT e.tenant_id, t.name, t.slug, count(*) FILTER (WHERE e.kind = 'REPLY') AS replies, count(*) FILTER (WHERE e.kind LIKE 'CHECK:%') AS corrections,
                       count(*) FILTER (WHERE e.kind IN ('ERROR', 'FALLBACK')) AS failures, count(*) FILTER (WHERE e.kind = 'NEEDS_HUMAN') AS handed_over
                FROM chat.ai_events e JOIN core.tenants t ON t.id = e.tenant_id WHERE e.created_at > now() - make_interval(days => :d)
                GROUP BY 1, 2, 3 ORDER BY 5 DESC, 4 DESC LIMIT 20
                """).param("d", d).query().listOfRows()));
        out.put("recentProblems", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT e.created_at, t.name AS shop, e.model, e.kind, e.detail, e.conversation_id FROM chat.ai_events e JOIN core.tenants t ON t.id = e.tenant_id
                WHERE e.kind <> 'REPLY' ORDER BY e.id DESC LIMIT 30
                """).query().listOfRows()));
        out.put("evalRuns", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT r.model, r.kind, r.overall_pct, r.checks, r.status, r.finished_at, t.name AS shop FROM chat.ai_eval_runs r JOIN core.tenants t ON t.id = r.tenant_id
                ORDER BY r.finished_at DESC LIMIT 20
                """).query().listOfRows()));
        out.put("backups", com.platform.shared.Rows.camel(jdbc.sql("SELECT kind, ok, size_bytes, offsite, detail, created_at FROM ops.backup_runs ORDER BY id DESC LIMIT 10").query().listOfRows()));
        return out;
    }
}
