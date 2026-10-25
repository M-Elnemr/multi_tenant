package com.platform.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import com.platform.shared.TenantContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Where the AI tokens go. Every request to the model is logged with its rounds (which tools the model asked for, and the tokens of that round), whatever the model or provider is,
 * and the platform admin reads it back per day, per shop, per conversation. Days are Cairo days.
 */
@Service
public class AiUsageService {
    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final ZoneId ZONE = ZoneId.of("Africa/Cairo");

    /** One model round: the tools the model asked for (empty = it wrote the final answer) and what the round cost. */
    public record Round(List<String> tools, AiProvider.Usage usage) {}

    private final JdbcClient jdbc;
    private final int retentionDays;
    private final String provider;
    private final double priceIn, priceOut, priceCached;

    public AiUsageService(JdbcClient jdbc, @Value("${app.ai.usage-retention-days:90}") int retentionDays, @Value("${app.ai.provider:gemini}") String provider,
                          @Value("${app.ai.price.input:0.50}") double priceIn, @Value("${app.ai.price.output:3.00}") double priceOut, @Value("${app.ai.price.cached:0.05}") double priceCached) {
        this.jdbc = jdbc; this.retentionDays = retentionDays; this.provider = provider; this.priceIn = priceIn; this.priceOut = priceOut; this.priceCached = priceCached;
    }

    // ---- writing ---------------------------------------------------------------------------------------------------------------

    /** Logs one request. Never lets a logging problem break the feature that called it. */
    public void record(UUID tenantId, String feature, String channel, UUID conversationId, boolean instant, String preview, List<Round> rounds) {
        try {
            int in = 0, out = 0, cached = 0;
            List<Map<String, Object>> calls = new ArrayList<>();
            for (Round r : rounds) {
                in += r.usage().input(); out += r.usage().output(); cached += r.usage().cached();
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("tools", r.tools()); c.put("in", r.usage().input()); c.put("out", r.usage().output()); c.put("cached", r.usage().cached());
                calls.add(c);
            }
            String clip = preview == null ? null : preview.replaceAll("\\s+", " ").trim();
            if (clip != null && clip.length() > 80) clip = clip.substring(0, 80);
            jdbc.sql("""
                    INSERT INTO chat.ai_usage_log (tenant_id, feature, channel, conversation_id, instant, model_calls, input_tokens, output_tokens, cached_tokens, calls, preview)
                    VALUES (:t, :f, :ch, :cv, :i, :mc, :in, :out, :k, CAST(:calls AS jsonb), :p)
                    """).param("t", tenantId).param("f", feature).param("ch", channel).param("cv", conversationId).param("i", instant).param("mc", rounds.size())
                    .param("in", in).param("out", out).param("k", cached).param("calls", JSON.writeValueAsString(calls)).param("p", clip).update();
        } catch (Exception e) { log.warn("AI usage not recorded: {}", e.toString()); }
    }

    /** A single model call from a feature that has no chat (posts, ads, translation...). The shop is the one of the current request, when there is one. */
    public void recordCall(UUID tenantIdOrNull, String feature, AiProvider.Turn t) {
        UUID tenant = tenantIdOrNull != null ? tenantIdOrNull : TenantContext.get() == null ? null : TenantContext.get().id();
        record(tenant, feature, null, null, false, null, List.of(new Round(t.calls().stream().map(AiProvider.ToolCall::name).toList(), t.usage())));
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        int n = jdbc.sql("DELETE FROM chat.ai_usage_log WHERE created_at < now() - make_interval(days => :d)").param("d", retentionDays).update();
        if (n > 0) log.info("Purged {} AI usage rows older than {} days", n, retentionDays);
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------

    private static LocalDate dayOf(String day) {
        try { return day == null || day.isBlank() ? LocalDate.now(ZONE) : LocalDate.parse(day); }
        catch (java.time.format.DateTimeParseException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "The date must look like 2026-10-25"); }
    }

    private static OffsetDateTime start(LocalDate d) { return d.atStartOfDay(ZONE).toOffsetDateTime(); }

    private static final String SUMS = "count(*) FILTER (WHERE NOT instant) AS requests, count(*) FILTER (WHERE instant) AS instant, coalesce(sum(model_calls),0) AS model_calls, "
            + "coalesce(sum(input_tokens),0) AS input, coalesce(sum(output_tokens),0) AS output, coalesce(sum(cached_tokens),0) AS cached";

    private Map<String, Object> prices() { return Map.of("provider", provider, "inputPerMillion", priceIn, "outputPerMillion", priceOut, "cachedPerMillion", priceCached); }

    private List<Map<String, Object>> rows(String sql, Map<String, Object> params) {
        var q = jdbc.sql(sql);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        return Rows.camel(q.query().listOfRows());
    }

    private List<Map<String, Object>> hours(LocalDate day, UUID tenant) {
        String tf = tenant == null ? "" : " AND tenant_id = :t";
        Map<String, Object> p = new LinkedHashMap<>(Map.of("f", start(day), "to", start(day.plusDays(1))));
        if (tenant != null) p.put("t", tenant);
        var found = new LinkedHashMap<Integer, Map<String, Object>>();
        for (var r : rows("SELECT extract(hour FROM created_at AT TIME ZONE 'Africa/Cairo')::int AS hour, " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to" + tf + " GROUP BY 1", p)) found.put(((Number) r.get("hour")).intValue(), r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) out.add(found.getOrDefault(h, Map.of("hour", h, "requests", 0L, "instant", 0L, "modelCalls", 0L, "input", 0L, "output", 0L, "cached", 0L)));
        return out;
    }

    private List<Map<String, Object>> steps(LocalDate day, UUID tenant) {
        String tf = tenant == null ? "" : " AND l.tenant_id = :t";
        Map<String, Object> p = new LinkedHashMap<>(Map.of("f", start(day), "to", start(day.plusDays(1))));
        if (tenant != null) p.put("t", tenant);
        return rows("""
                SELECT coalesce(nullif(array_to_string(ARRAY(SELECT jsonb_array_elements_text(c -> 'tools')), ', '), ''), '(final answer)') AS step, l.feature, count(*) AS rounds,
                  sum((c ->> 'in')::int) AS input, sum((c ->> 'out')::int) AS output, sum((c ->> 'cached')::int) AS cached
                FROM chat.ai_usage_log l, jsonb_array_elements(l.calls) c
                WHERE l.created_at >= :f AND l.created_at < :to""" + tf + " GROUP BY 1, 2 ORDER BY sum((c ->> 'in')::int) + sum((c ->> 'out')::int) DESC", p);
    }

    /** The whole platform on one day. */
    public Map<String, Object> day(String dayText) {
        LocalDate day = dayOf(dayText);
        Map<String, Object> p = Map.of("f", start(day), "to", start(day.plusDays(1)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("prices", prices());
        out.put("totals", rows("SELECT " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to", p).get(0));
        out.put("byFeature", rows("SELECT feature, " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to GROUP BY feature ORDER BY sum(input_tokens + output_tokens) DESC", p));
        out.put("byChannel", rows("SELECT coalesce(channel, '-') AS channel, " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to AND feature = 'CHAT' GROUP BY 1 ORDER BY sum(input_tokens + output_tokens) DESC", p));
        out.put("byStep", steps(day, null));
        out.put("byHour", hours(day, null));
        out.put("shops", rows("""
                SELECT l.tenant_id, coalesce(t.name, '—') AS name, count(DISTINCT l.conversation_id) AS conversations, """ + SUMS + """
                 FROM chat.ai_usage_log l LEFT JOIN core.tenants t ON t.id = l.tenant_id WHERE l.created_at >= :f AND l.created_at < :to
                 GROUP BY l.tenant_id, t.name ORDER BY sum(l.input_tokens + l.output_tokens) DESC
                """, p));
        out.put("series", rows("SELECT (created_at AT TIME ZONE 'Africa/Cairo')::date AS day, " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to GROUP BY 1 ORDER BY 1",
                Map.of("f", start(day.minusDays(29)), "to", start(day.plusDays(1)))));
        return out;
    }

    /** One shop on one day: its conversations, features, steps and hours. */
    public Map<String, Object> shop(UUID tenantId, String dayText) {
        LocalDate day = dayOf(dayText);
        Map<String, Object> p = Map.of("f", start(day), "to", start(day.plusDays(1)), "t", tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("prices", prices());
        out.put("shop", jdbc.sql("SELECT id, name FROM core.tenants WHERE id = :t").param("t", tenantId).query().listOfRows().stream().findFirst().map(Rows::camel).orElse(Map.of("id", tenantId, "name", "—")));
        out.put("totals", rows("SELECT " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to AND tenant_id = :t", p).get(0));
        out.put("byFeature", rows("SELECT feature, " + SUMS + " FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to AND tenant_id = :t GROUP BY feature ORDER BY sum(input_tokens + output_tokens) DESC", p));
        out.put("byStep", steps(day, tenantId));
        out.put("byHour", hours(day, tenantId));
        out.put("conversations", rows("""
                SELECT conversation_id, max(channel) AS channel, min(created_at) AS first_at, max(created_at) AS last_at, (array_agg(preview ORDER BY id) FILTER (WHERE preview IS NOT NULL))[1] AS first_message, """ + SUMS + """
                 FROM chat.ai_usage_log WHERE created_at >= :f AND created_at < :to AND tenant_id = :t AND conversation_id IS NOT NULL
                 GROUP BY conversation_id ORDER BY sum(input_tokens + output_tokens) DESC LIMIT 300
                """, p));
        out.put("other", rows("""
                SELECT id, created_at, feature, model_calls, input_tokens AS input, output_tokens AS output, cached_tokens AS cached FROM chat.ai_usage_log
                 WHERE created_at >= :f AND created_at < :to AND tenant_id = :t AND conversation_id IS NULL ORDER BY id DESC LIMIT 200
                """, p));
        return out;
    }

    /** Every reply of one conversation, with the rounds behind it. */
    public Map<String, Object> conversation(UUID conversationId) {
        var replies = rows("""
                SELECT id, created_at, feature, instant, preview, model_calls, input_tokens AS input, output_tokens AS output, cached_tokens AS cached, calls::text AS calls, channel, tenant_id
                 FROM chat.ai_usage_log WHERE conversation_id = :c ORDER BY id
                """, Map.of("c", conversationId));
        if (replies.isEmpty()) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "No AI usage was recorded for this conversation");
        for (var r : replies) {
            try { r.put("calls", JSON.readValue((String) r.get("calls"), List.class)); } catch (Exception e) { r.put("calls", List.of()); }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conversationId);
        out.put("prices", prices());
        out.put("channel", replies.get(0).get("channel"));
        out.put("replies", replies);
        return out;
    }
}
