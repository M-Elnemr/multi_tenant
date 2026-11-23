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
    private final KnowledgeIndex knowledge;

    public AiHealthController(JdbcClient jdbc, KnowledgeIndex knowledge) { this.jdbc = jdbc; this.knowledge = knowledge; }

    /** Search by meaning per shop: how much of each catalog is indexed, embedded and has its search words. */
    @GetMapping("/knowledge")
    public Map<String, Object> knowledge() { return knowledge.overview(); }

    /** Brings one shop's index in step now (normally the background job does it within minutes of an edit). */
    @org.springframework.web.bind.annotation.PostMapping("/knowledge/{tenantId}/reindex")
    public Map<String, Object> reindex(@org.springframework.web.bind.annotation.PathVariable java.util.UUID tenantId) {
        if (!knowledge.enabled()) throw new com.platform.shared.BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDINGS_OFF", "Search by meaning is off: no embedding key, or the provider refused it a few minutes ago");
        return knowledge.reindexNow(tenantId);
    }

    /**
     * Search by meaning (RAG) reliability: the embedding provider's own counters (calls, failures by kind, retries that saved a query, latency, resting) and, per day, how many replies were made
     * while it was unavailable (the model was told, never a silent words-only search), with the shops and chats affected.
     */
    @GetMapping("/rag")
    public Map<String, Object> rag(@RequestParam(defaultValue = "7") int days) {
        int d = Math.max(1, Math.min(60, days));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embedder", knowledge.health());
        out.put("byDay", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT to_char(created_at AT TIME ZONE 'Africa/Cairo', 'YYYY-MM-DD') AS day,
                       count(*) FILTER (WHERE kind = 'RAG_UNAVAILABLE') AS replies_without_rag, count(DISTINCT conversation_id) FILTER (WHERE kind = 'RAG_UNAVAILABLE') AS chats_affected,
                       count(*) FILTER (WHERE kind = 'REPLY') AS replies
                FROM chat.ai_events WHERE created_at > now() - make_interval(days => :d) GROUP BY 1 ORDER BY 1 DESC
                """).param("d", d).query().listOfRows()));
        out.put("recent", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT e.created_at, t.name AS shop, e.detail, e.conversation_id FROM chat.ai_events e JOIN core.tenants t ON t.id = e.tenant_id
                WHERE e.kind = 'RAG_UNAVAILABLE' ORDER BY e.id DESC LIMIT 20
                """).query().listOfRows()));
        return out;
    }

    @GetMapping
    public Map<String, Object> health(@RequestParam(defaultValue = "7") int days) {
        int d = Math.max(1, Math.min(60, days));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("rag", knowledge.health());
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
        out.put("slowest", com.platform.shared.Rows.camel(jdbc.sql("""
                SELECT e.created_at, t.name AS shop, e.model, e.detail, e.ms, e.conversation_id FROM chat.ai_events e JOIN core.tenants t ON t.id = e.tenant_id
                WHERE e.kind = 'REPLY' AND e.created_at > now() - make_interval(days => :d) ORDER BY e.ms DESC NULLS LAST LIMIT 15
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
