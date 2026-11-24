package com.platform.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Phase 0 measurement, side by side for the admin: the replies answered with the classic request layout and with the optimized one (the «AI optimization» switch), read from
 * {@code chat.reply_traces} (read only). Per layout: replies, model calls per reply, input tokens and the cached share, output tokens, how often a rewrite, a review and another model's
 * second opinion happened, reply time (median and 90th percentile), the size of each part of the request, and the rule checks that fired most. Replies traced before the switch existed
 * count as classic. Nothing here is a message, a prompt or an id of a customer.
 */
@Service
public class AiLayoutCompare {
    private final JdbcClient jdbc;

    public AiLayoutCompare(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** {@code days}: 1 to 60; {@code source}: ALL, REPLY (real customers) or TEST (the owners' and the benchmark's test chats). */
    public Map<String, Object> compare(int days, String source) {
        int d = Math.max(1, Math.min(days, 60));
        String src = source == null ? "ALL" : source.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ALL", "REPLY", "TEST", "EVAL").contains(src)) src = "ALL";
        List<Map<String, Object>> rows = jdbc.sql("""
                WITH t AS (
                  SELECT coalesce(summary->>'prompt_mode', 'classic') AS mode, reply_ms, coalesce(model_calls, 0) AS calls,
                         coalesce((summary->>'tokens_in')::bigint, 0) AS tin, coalesce((summary->>'tokens_out')::bigint, 0) AS tout,
                         coalesce((summary->>'tokens_cached')::bigint,
                           (SELECT coalesce(sum((e->'a'->>'cached')::bigint), 0) FROM jsonb_array_elements(events) e WHERE e->>'n' = 'provider_call' AND e->'a'->>'usable' = 'true')) AS cached,
                         coalesce((summary->>'rewrites')::int, 0) AS rewrites,
                         coalesce(summary->>'review_verdict', 'none') AS verdict,
                         EXISTS (SELECT 1 FROM jsonb_array_elements(events) e WHERE e->>'n' = 'provider_call' AND e->'a'->>'role' = 'second_opinion') AS second,
                         coalesce((summary->>'second_opinions_skipped')::int, 0) AS skipped,
                         (summary->>'static_chars')::bigint AS static_chars, (summary->>'dynamic_chars')::bigint AS dynamic_chars,
                         (summary->>'history_chars')::bigint AS history_chars, (summary->>'tools_chars')::bigint AS tools_chars,
                         outcome
                  FROM chat.reply_traces
                  WHERE started_at > now() - make_interval(days => :d) AND (:src = 'ALL' OR source = :src) AND coalesce(model_calls, 0) > 0
                )
                SELECT mode, count(*) AS replies, round(avg(calls), 2) AS calls_per_reply, round(avg(tin)) AS tokens_in, round(avg(tout)) AS tokens_out,
                       round(100.0 * sum(cached) / nullif(sum(tin), 0), 1) AS cached_pct,
                       round(avg(tin) / nullif(avg(calls), 0)) AS tokens_in_per_call,
                       round(100.0 * avg((rewrites > 0)::int), 1) AS rewrite_pct,
                       round(100.0 * avg((verdict NOT IN ('none', 'skipped'))::int), 1) AS review_pct,
                       round(100.0 * avg(second::int), 1) AS second_opinion_pct, sum(skipped) AS second_opinions_skipped,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY reply_ms) AS reply_ms_p50, percentile_cont(0.9) WITHIN GROUP (ORDER BY reply_ms) AS reply_ms_p90,
                       round(avg(static_chars)) AS static_chars, round(avg(dynamic_chars)) AS dynamic_chars, round(avg(history_chars)) AS history_chars, round(avg(tools_chars)) AS tools_chars,
                       round(100.0 * avg((outcome <> 'completed')::int), 1) AS failed_pct
                FROM t GROUP BY mode ORDER BY mode
                """).param("d", d).param("src", src).query().listOfRows();
        Map<String, Map<String, Long>> checks = new TreeMap<>();
        for (var r : jdbc.sql("""
                SELECT coalesce(t.summary->>'prompt_mode', 'classic') AS mode, e->'a'->>'kinds' AS kinds
                FROM chat.reply_traces t, jsonb_array_elements(t.events) e
                WHERE t.started_at > now() - make_interval(days => :d) AND (:src = 'ALL' OR t.source = :src) AND e->>'n' = 'rule_checks' AND coalesce(e->'a'->>'kinds', '') <> ''
                """).param("d", d).param("src", src).query().listOfRows()) {
            var byKind = checks.computeIfAbsent(String.valueOf(r.get("mode")), k -> new LinkedHashMap<>());
            for (String k : String.valueOf(r.get("kinds")).split(":")) if (k.matches("[A-Z_]{3,}")) byKind.merge(k, 1L, Long::sum);
        }
        List<Map<String, Object>> layouts = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            var byKind = checks.getOrDefault(String.valueOf(r.get("mode")), Map.of());
            m.put("topChecks", byKind.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
                    .map(e -> Map.<String, Object>of("kind", e.getKey(), "count", e.getValue())).toList());
            layouts.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("source", src);
        out.put("layouts", layouts);
        return out;
    }
}
