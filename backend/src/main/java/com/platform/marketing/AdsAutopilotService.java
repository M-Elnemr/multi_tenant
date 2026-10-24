package com.platform.marketing;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Optimizes ads the owner already launched, inside hard limits the owner set. It can only: pause an ad whose cost per click is far above target, raise a winning ad's
 * budget in small steps, lower budgets that exceed the daily cap, and keep the best of several texts. It never creates a campaign and never spends beyond the caps
 * (a cap of 0 means "do not raise"). Every action is written to {@code marketing.ad_actions} and can be undone.
 */
@Service
public class AdsAutopilotService {
    private static final Logger log = LoggerFactory.getLogger(AdsAutopilotService.class);
    static final int MIN_IMPRESSIONS = 1000, MIN_CLICKS_TO_PAUSE = 15, MIN_CLICKS_TO_RAISE = 40, VARIANT_DAYS = 3, VARIANT_MIN_CLICKS = 10;

    private final JdbcClient jdbc;
    private final AdsService ads;
    private final AiContentService content;
    private final AuditService audit;
    private final boolean scheduler;

    public AdsAutopilotService(JdbcClient jdbc, AdsService ads, AiContentService content, AuditService audit, @org.springframework.beans.factory.annotation.Value("${app.integrations.scheduler.enabled:true}") boolean scheduler) {
        this.jdbc = jdbc;
        this.ads = ads;
        this.content = content;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    // ---- guardrails --------------------------------------------------------------------------------------------------------

    public Map<String, Object> guardrails(UUID t) {
        var r = jdbc.sql("SELECT enabled, daily_cap_minor, monthly_cap_minor, max_step_pct, target_cpc_minor FROM marketing.guardrails WHERE tenant_id = :t").param("t", t).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", r != null && Boolean.TRUE.equals(r.get("enabled")));
        m.put("dailyCapMinor", r == null ? 0 : r.get("daily_cap_minor"));
        m.put("monthlyCapMinor", r == null ? 0 : r.get("monthly_cap_minor"));
        m.put("maxStepPct", r == null ? 20 : r.get("max_step_pct"));
        m.put("targetCpcMinor", r == null ? 0 : r.get("target_cpc_minor"));
        return m;
    }

    public record GuardrailsReq(Boolean enabled, Long dailyCapMinor, Long monthlyCapMinor, Integer maxStepPct, Long targetCpcMinor) {}

    public Map<String, Object> saveGuardrails(UUID t, UUID actor, GuardrailsReq r) {
        var cur = guardrails(t);
        boolean enabled = r.enabled() != null ? r.enabled() : (Boolean) cur.get("enabled");
        long daily = r.dailyCapMinor() != null ? r.dailyCapMinor() : ((Number) cur.get("dailyCapMinor")).longValue();
        long monthly = r.monthlyCapMinor() != null ? r.monthlyCapMinor() : ((Number) cur.get("monthlyCapMinor")).longValue();
        int step = r.maxStepPct() != null ? r.maxStepPct() : ((Number) cur.get("maxStepPct")).intValue();
        long target = r.targetCpcMinor() != null ? r.targetCpcMinor() : ((Number) cur.get("targetCpcMinor")).longValue();
        if (daily < 0 || monthly < 0 || target < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Amounts cannot be negative");
        if (step < 5 || step > 50) throw BusinessException.badRequest("VALIDATION_ERROR", "The maximum step must be between 5% and 50%");
        if (monthly > 0 && daily > monthly) throw BusinessException.badRequest("VALIDATION_ERROR", "The daily cap cannot be larger than the monthly cap");
        if (enabled && target <= 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Set the cost per click you are happy with before turning the optimizer on");
        jdbc.sql("""
                INSERT INTO marketing.guardrails (tenant_id, enabled, daily_cap_minor, monthly_cap_minor, max_step_pct, target_cpc_minor) VALUES (:t, :e, :d, :m, :s, :c)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = :e, daily_cap_minor = :d, monthly_cap_minor = :m, max_step_pct = :s, target_cpc_minor = :c, updated_at = now()
                """).param("t", t).param("e", enabled).param("d", daily).param("m", monthly).param("s", step).param("c", target).update();
        audit.record(actor, t, "AD_GUARDRAILS_CHANGED", "marketing_guardrails", null, null);
        return guardrails(t);
    }

    // ---- reading what happened -----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> actions(UUID t) {
        return Rows.camel(jdbc.sql("SELECT a.id, a.campaign_id, c.name AS campaign, a.kind, a.reason, a.before_value, a.after_value, a.created_at, a.undone_at FROM marketing.ad_actions a JOIN marketing.campaigns c ON c.id = a.campaign_id WHERE a.tenant_id = :t ORDER BY a.id DESC LIMIT 100")
                .param("t", t).query().listOfRows());
    }

    private void log(UUID t, UUID campaign, String kind, String reason, Long before, Long after, String ref) {
        jdbc.sql("INSERT INTO marketing.ad_actions (tenant_id, campaign_id, kind, reason, before_value, after_value, ref) VALUES (:t, :c, :k, :r, :b, :a, :f)")
                .param("t", t).param("c", campaign).param("k", kind).param("r", reason.length() > 390 ? reason.substring(0, 390) : reason).param("b", before).param("a", after).param("f", ref).update();
    }

    // ---- the optimizer -------------------------------------------------------------------------------------------------------

    private static double num(Object o) { try { return o == null ? 0 : Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; } }

    /** Spend so far this month, in minor units, from the results Meta reported for campaigns that started this month, plus what active campaigns will still spend. */
    private long projectedMonth(UUID t, long dailyTotal) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long spent = 0;
        for (var c : jdbc.sql("SELECT insights::text AS insights FROM marketing.campaigns WHERE tenant_id = :t AND created_at >= :m").param("t", t).param("m", Timestamp.valueOf(today.withDayOfMonth(1).atStartOfDay())).query().listOfRows())
            spent += Math.round(num(((Map<?, ?>) Rows.json(c.get("insights"))).get("spend")) * 100);
        int daysLeft = today.lengthOfMonth() - today.getDayOfMonth();
        return spent + dailyTotal * daysLeft;
    }

    private long activeDailyTotal(UUID t, UUID except, long exceptValue) {
        long total = 0;
        for (var c : jdbc.sql("SELECT id, daily_budget_minor FROM marketing.campaigns WHERE tenant_id = :t AND status = 'ACTIVE'").param("t", t).query().listOfRows())
            total += c.get("id").equals(except) ? exceptValue : ((Number) c.get("daily_budget_minor")).longValue();
        return total;
    }

    /** One pass for one shop. Returns what it did. */
    public List<Map<String, Object>> optimize(UUID t) {
        var g = guardrails(t);
        if (!Boolean.TRUE.equals(g.get("enabled"))) return List.of();
        long daily = ((Number) g.get("dailyCapMinor")).longValue(), monthly = ((Number) g.get("monthlyCapMinor")).longValue(), target = ((Number) g.get("targetCpcMinor")).longValue();
        int step = ((Number) g.get("maxStepPct")).intValue();
        int before = actions(t).size();
        for (var c : jdbc.sql("SELECT id, daily_budget_minor FROM marketing.campaigns WHERE tenant_id = :t AND status = 'ACTIVE' AND external_id IS NOT NULL").param("t", t).query().listOfRows()) {
            UUID id = (UUID) c.get("id");
            try {
                ads.refresh(t, id);
                var row = jdbc.sql("SELECT status, daily_budget_minor, insights::text AS insights FROM marketing.campaigns WHERE id = :i").param("i", id).query().singleRow();
                if (!"ACTIVE".equals(row.get("status"))) continue;
                Map<?, ?> ins = (Map<?, ?>) Rows.json(row.get("insights"));
                double impressions = num(ins.get("impressions")), clicks = num(ins.get("clicks")), spend = num(ins.get("spend"));
                if (impressions < MIN_IMPRESSIONS || clicks <= 0) continue;
                long cpc = Math.round(spend * 100 / clicks);
                long budget = ((Number) row.get("daily_budget_minor")).longValue();
                if (cpc > target * 2 && clicks >= MIN_CLICKS_TO_PAUSE) {
                    ads.setStatus(t, null, id, "PAUSED");
                    log(t, id, "PAUSE", "Cost per click " + cpc + " is more than double the target " + target, budget, 0L, null);
                } else if (cpc * 10 < target * 7 && clicks >= MIN_CLICKS_TO_RAISE && daily > 0 && monthly > 0) {
                    long next = budget + budget * step / 100;
                    long totalAfter = activeDailyTotal(t, id, next);
                    if (next > budget && totalAfter <= daily && projectedMonth(t, totalAfter) <= monthly) {
                        ads.setDailyBudget(t, id, next);
                        log(t, id, "RAISE_BUDGET", "Cost per click " + cpc + " is well under the target " + target + " with " + (long) clicks + " clicks", budget, next, null);
                    }
                }
            } catch (RuntimeException e) {
                AdsAutopilotService.log.debug("Optimizer skipped campaign {}: {}", id, e.getMessage());
            }
        }
        enforceDailyCap(t, daily);
        pickWinners(t);
        var all = actions(t);
        return all.subList(0, Math.max(0, all.size() - before));
    }

    /** If the active budgets add up to more than the daily cap, the biggest one is lowered until they fit. */
    private void enforceDailyCap(UUID t, long daily) {
        if (daily <= 0) return;
        for (int i = 0; i < 10; i++) {
            long total = activeDailyTotal(t, null, 0);
            if (total <= daily) return;
            var big = jdbc.sql("SELECT id, daily_budget_minor FROM marketing.campaigns WHERE tenant_id = :t AND status = 'ACTIVE' ORDER BY daily_budget_minor DESC LIMIT 1").param("t", t).query().singleRow();
            long cur = ((Number) big.get("daily_budget_minor")).longValue(), next = Math.max(500, cur - (total - daily));
            if (next >= cur) return;
            try {
                ads.setDailyBudget(t, (UUID) big.get("id"), next);
                log(t, (UUID) big.get("id"), "LOWER_BUDGET", "Active budgets (" + total + ") were above your daily cap (" + daily + ")", cur, next, null);
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ---- copy variants ---------------------------------------------------------------------------------------------------------

    /** Makes three Arabic (or English) versions of the campaign's text and runs them as separate ads. */
    public List<Map<String, Object>> startVariants(UUID t, UUID campaignId, String language) {
        var c = jdbc.sql("SELECT c.product_id, c.currency, p.name, p.description, coalesce(sp.store_name, tn.name) AS shop, (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS price "
                + "FROM marketing.campaigns c JOIN core.tenants tn ON tn.id = c.tenant_id LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = c.tenant_id LEFT JOIN commerce.products p ON p.id = c.product_id WHERE c.id = :c AND c.tenant_id = :t")
                .param("c", campaignId).param("t", t).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Campaign not found"));
        if (jdbc.sql("SELECT count(*) FROM marketing.copy_variants WHERE campaign_id = :c").param("c", campaignId).query(Long.class).single() > 0) throw BusinessException.conflict("VARIANTS_EXIST", "This campaign already has text variants");
        String cur = String.valueOf(c.get("currency"));
        var texts = content.adVariants(new AiContentService.AdContext("SAR".equals(cur) ? "SA" : "AED".equals(cur) ? "AE" : "EG", cur, (String) c.get("name"), (String) c.get("description"), null, c.get("price") == null ? 0 : ((Number) c.get("price")).longValue(), (String) c.get("shop")), language);
        return ads.addVariants(t, campaignId, texts);
    }

    public List<Map<String, Object>> variants(UUID t, UUID campaignId) {
        return Rows.camel(jdbc.sql("SELECT id, headline, primary_text, status, insights::text AS insights, created_at FROM marketing.copy_variants WHERE tenant_id = :t AND campaign_id = :c ORDER BY created_at").param("t", t).param("c", campaignId).query().listOfRows()).stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>(v);
            m.put("insights", Rows.json(v.get("insights")));
            return m;
        }).toList();
    }

    /** After {@value #VARIANT_DAYS} days, keeps the variant with the lowest cost per click (enough clicks to tell) and pauses the others. */
    public void pickWinners(UUID t) {
        for (var camp : jdbc.sql("SELECT DISTINCT campaign_id FROM marketing.copy_variants WHERE tenant_id = :t AND status = 'RUNNING' AND created_at < now() - make_interval(days => :d)").param("t", t).param("d", VARIANT_DAYS).query().listOfRows()) {
            UUID cid = (UUID) camp.get("campaign_id");
            try {
                ads.refreshVariants(t, cid);
                decide(t, cid);
            } catch (RuntimeException e) {
                log.debug("Variant decision skipped for {}: {}", cid, e.getMessage());
            }
        }
    }

    /** Decides from the stored per-variant results (public so a test can set them). */
    public void decide(UUID t, UUID cid) {
        var vs = jdbc.sql("SELECT id, ad_external_id, headline, insights::text AS insights FROM marketing.copy_variants WHERE campaign_id = :c AND status = 'RUNNING'").param("c", cid).query().listOfRows();
        UUID best = null;
        double bestCpc = Double.MAX_VALUE;
        for (var v : vs) {
            Map<?, ?> ins = (Map<?, ?>) Rows.json(v.get("insights"));
            double clicks = num(ins.get("clicks")), spend = num(ins.get("spend")), imp = num(ins.get("impressions"));
            if (clicks < VARIANT_MIN_CLICKS || imp < 300) return;   // not enough evidence in one of them: wait
            double cpc = spend / clicks;
            if (cpc < bestCpc) { bestCpc = cpc; best = (UUID) v.get("id"); }
        }
        if (best == null) return;
        for (var v : vs) {
            boolean win = v.get("id").equals(best);
            jdbc.sql("UPDATE marketing.copy_variants SET status = :s WHERE id = :i").param("s", win ? "WINNER" : "PAUSED").param("i", v.get("id")).update();
            if (!win) {
                if (v.get("ad_external_id") != null) ads.setAdStatus(t, cid, (String) v.get("ad_external_id"), false);
                log(t, cid, "PAUSE_VARIANT", "Text \"" + v.get("headline") + "\" cost more per click than the winner", null, null, (String) v.get("ad_external_id"));
            } else {
                log(t, cid, "KEEP_VARIANT", "Text \"" + v.get("headline") + "\" had the lowest cost per click (" + Math.round(bestCpc * 100) + ")", null, null, (String) v.get("ad_external_id"));
            }
        }
    }

    // ---- undo -----------------------------------------------------------------------------------------------------------------------

    public Map<String, Object> undo(UUID t, UUID actor, long actionId) {
        var a = jdbc.sql("SELECT id, campaign_id, kind, before_value, after_value, ref, undone_at FROM marketing.ad_actions WHERE id = :i AND tenant_id = :t").param("i", actionId).param("t", t).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Action not found"));
        if (a.get("undone_at") != null) throw BusinessException.conflict("ALREADY_UNDONE", "Already undone");
        UUID cid = (UUID) a.get("campaign_id");
        switch ((String) a.get("kind")) {
            case "PAUSE" -> ads.setStatus(t, actor, cid, "ACTIVE");
            case "RAISE_BUDGET", "LOWER_BUDGET" -> {
                long back = ((Number) a.get("before_value")).longValue();
                var g = guardrails(t);
                long daily = ((Number) g.get("dailyCapMinor")).longValue();
                if (daily > 0 && activeDailyTotal(t, cid, back) > daily) throw BusinessException.conflict("OVER_CAP", "Restoring that budget would go above your daily cap");
                ads.setDailyBudget(t, cid, back);
            }
            case "PAUSE_VARIANT" -> {
                if (a.get("ref") != null) ads.setAdStatus(t, cid, (String) a.get("ref"), true);
                jdbc.sql("UPDATE marketing.copy_variants SET status = 'RUNNING' WHERE campaign_id = :c AND ad_external_id = :r").param("c", cid).param("r", a.get("ref")).update();
            }
            default -> throw BusinessException.badRequest("VALIDATION_ERROR", "This action cannot be undone");
        }
        jdbc.sql("UPDATE marketing.ad_actions SET undone_at = now() WHERE id = :i").param("i", actionId).update();
        return Map.of("undone", true);
    }

    // ---- schedule ---------------------------------------------------------------------------------------------------------------------

    @Scheduled(cron = "0 40 */6 * * *")
    public void optimizeAll() {
        if (!scheduler) return;
        for (UUID t : jdbc.sql("SELECT tenant_id FROM marketing.guardrails WHERE enabled").query(UUID.class).list()) {
            try { optimize(t); } catch (RuntimeException e) { log.warn("Ad optimizer failed for {}: {}", t, e.getMessage()); }
        }
    }
}
