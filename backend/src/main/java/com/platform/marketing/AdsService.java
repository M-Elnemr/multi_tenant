package com.platform.marketing;

import com.platform.audit.AuditService;
import com.platform.chat.MetaGraph;
import com.platform.integrations.IntegrationService;
import com.platform.integrations.IntegrationCrypto;
import com.platform.integrations.Providers;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * Meta ad campaigns made from the dashboard: AI-suggested settings the owner can change, campaign + ad set + creative + ad created through the Marketing API (always paused first,
 * then launched on purpose), and the results read back. The money is spent from the owner's own Meta ad account.
 */
@Service
public class AdsService {
    private static final Logger log = LoggerFactory.getLogger(AdsService.class);
    private static final List<String> OBJECTIVES = List.of("OUTCOME_TRAFFIC", "OUTCOME_ENGAGEMENT", "OUTCOME_AWARENESS");

    private final JdbcClient jdbc;
    private final MetaGraph graph;
    private final IntegrationService integrations;
    private final IntegrationCrypto crypto;
    private final AiContentService content;
    private final SocialService social;
    private final OpenUrls urls;
    private final AuditService audit;

    public AdsService(JdbcClient jdbc, MetaGraph graph, IntegrationService integrations, IntegrationCrypto crypto, AiContentService content, SocialService social, OpenUrls urls, AuditService audit) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.integrations = integrations;
        this.crypto = crypto;
        this.content = content;
        this.social = social;
        this.urls = urls;
        this.audit = audit;
    }

    /** Called from the Facebook login picker: keeps the chosen ad account and the user token that can run ads on it. */
    public Map<String, Object> connectAccount(UUID tenantId, UUID actor, String adAccountId, String name, String userToken, String currency) {
        Map<String, String> cred = new LinkedHashMap<>();
        cred.put("userToken", userToken);
        cred.put("currency", currency == null ? "EGP" : currency);
        String country = "SAR".equals(currency) ? "SA" : "AED".equals(currency) ? "AE" : "EG";
        return integrations.store(tenantId, actor, Providers.get("META_ADS"), country, name, adAccountIdKey(adAccountId, cred));
    }

    private static Map<String, String> adAccountIdKey(String id, Map<String, String> cred) { cred.put("adAccountId", id.startsWith("act_") ? id : "act_" + id); return cred; }

    public List<Map<String, Object>> adAccounts(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, label, external_id, country, status, last_error FROM integrations.accounts WHERE tenant_id = :t AND provider = 'META_ADS' ORDER BY created_at").param("t", tenantId).query().listOfRows());
    }

    // ---- suggestion ---------------------------------------------------------------------------------------------

    public Map<String, Object> suggest(UUID tenantId, UUID accountId, UUID productId, String language) {
        var account = integrations.row(tenantId, accountId);
        String currency = integrations.credentials(account).getOrDefault("currency", "EGP");
        var shop = jdbc.sql("SELECT coalesce(sp.store_name, t.name) AS name, t.default_locale FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t").param("t", tenantId).query().singleRow();
        String productName = null, description = null, category = null;
        long price = 0;
        if (productId != null) {
            var p = jdbc.sql("""
                    SELECT p.name, p.description, tx.name_en AS category, (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS price
                    FROM commerce.products p LEFT JOIN commerce.taxonomy tx ON tx.id = p.taxonomy_id WHERE p.id = :p AND p.tenant_id = :t
                    """).param("p", productId).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
            productName = (String) p.get("name"); description = (String) p.get("description"); category = (String) p.get("category"); price = p.get("price") == null ? 0 : ((Number) p.get("price")).longValue();
        }
        String lang = language == null || language.isBlank() ? (String) shop.get("default_locale") : language;
        return content.adSuggestion(new AiContentService.AdContext((String) account.get("country"), currency, productName, description, category, price, (String) shop.get("name")), lang);
    }

    // ---- creating ------------------------------------------------------------------------------------------------

    public record CampaignReq(UUID accountId, UUID productId, UUID postId, String pageId, String name, String objective, Long dailyBudgetMinor, Instant startsAt, Integer durationDays, List<String> countries,
                              Integer ageMin, Integer ageMax, List<String> genders, List<String> interests, String headline, String primaryText, String cta, String imageUrl, String linkUrl, Boolean launch) {}

    public Map<String, Object> create(UUID tenantId, UUID actor, CampaignReq r) {
        var account = integrations.row(tenantId, r.accountId());
        if (!"META_ADS".equals(account.get("provider"))) throw BusinessException.badRequest("VALIDATION_ERROR", "Choose an ad account");
        String objective = OBJECTIVES.contains(r.objective()) ? r.objective() : "OUTCOME_TRAFFIC";
        long budget = r.dailyBudgetMinor() == null ? 0 : r.dailyBudgetMinor();
        if (budget < 500) throw BusinessException.badRequest("VALIDATION_ERROR", "The daily budget is too small");
        if (budget > 100_000_000L) throw BusinessException.badRequest("VALIDATION_ERROR", "The daily budget is too large");
        int days = r.durationDays() == null ? 7 : Math.max(1, Math.min(r.durationDays(), 90));
        List<String> countries = r.countries() == null || r.countries().isEmpty() ? List.of((String) account.get("country")) : r.countries().stream().map(String::toUpperCase).filter((c) -> c.matches("[A-Z]{2}")).distinct().toList();
        int ageMin = Math.max(18, r.ageMin() == null ? 18 : r.ageMin()), ageMax = Math.min(65, Math.max(ageMin, r.ageMax() == null ? 65 : r.ageMax()));
        String page = r.pageId();
        if (page == null || page.isBlank()) page = jdbc.sql("SELECT external_id FROM chat.channels WHERE tenant_id = :t AND platform = 'FACEBOOK' AND status = 'ACTIVE' ORDER BY created_at LIMIT 1").param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.badRequest("NO_PAGE", "Connect your Facebook page first: ads are shown on behalf of a page"));
        else if (jdbc.sql("SELECT count(*) FROM chat.channels WHERE tenant_id = :t AND platform = 'FACEBOOK' AND external_id = :e").param("t", tenantId).param("e", page).query(Long.class).single() == 0) throw BusinessException.badRequest("NO_PAGE", "That page is not connected");
        String link = r.linkUrl(), image = r.imageUrl();
        if (r.productId() != null) {
            var p = jdbc.sql("SELECT slug, (SELECT m.file_id FROM commerce.product_media m WHERE m.product_id = p.id AND m.file_id IS NOT NULL ORDER BY m.sort_order LIMIT 1) AS file FROM commerce.products p WHERE p.id = :p AND p.tenant_id = :t").param("p", r.productId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
            String host = urls.hosts(List.of(tenantId)).get(tenantId);
            if (link == null || link.isBlank()) link = urls.baseUrl(host) + "/products/" + URLEncoder.encode((String) p.get("slug"), StandardCharsets.UTF_8);
            if ((image == null || image.isBlank()) && p.get("file") != null) image = social.publicUrl(tenantId, (UUID) p.get("file"));
        }
        if (r.postId() != null && (image == null || image.isBlank())) { var post = social.get(tenantId, r.postId()); image = (String) post.get("imageUrl"); }
        if (link == null || link.isBlank()) link = urls.baseUrl(urls.hosts(List.of(tenantId)).get(tenantId));
        if (image == null || image.isBlank()) throw BusinessException.badRequest("IMAGE_REQUIRED", "The ad needs a picture");
        if (r.headline() == null || r.headline().isBlank() || r.primaryText() == null || r.primaryText().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "The ad needs a headline and a text");
        String name = r.name() == null || r.name().isBlank() ? (r.headline().length() > 60 ? r.headline().substring(0, 60) : r.headline()) : r.name().trim();
        String cta = List.of("SHOP_NOW", "LEARN_MORE", "ORDER_NOW", "SIGN_UP", "CONTACT_US").contains(r.cta()) ? r.cta() : "SHOP_NOW";
        Instant start = r.startsAt() == null || r.startsAt().isBefore(Instant.now().plusSeconds(120)) ? Instant.now().plusSeconds(180) : r.startsAt();
        Instant end = start.plus(days, ChronoUnit.DAYS);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("countries", countries); params.put("ageMin", ageMin); params.put("ageMax", ageMax); params.put("genders", r.genders() == null ? List.of() : r.genders()); params.put("interests", r.interests() == null ? List.of() : r.interests());
        params.put("headline", r.headline()); params.put("primaryText", r.primaryText()); params.put("cta", cta); params.put("imageUrl", image); params.put("linkUrl", link); params.put("pageId", page); params.put("durationDays", days);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO marketing.campaigns (tenant_id, account_id, product_id, name, objective, status, daily_budget_minor, currency, starts_at, ends_at, params, created_by)
                    VALUES (:t, :a, :p, :n, :o, 'DRAFT', :b, :c, :s, :e, CAST(:pr AS jsonb), :u) RETURNING id
                    """).param("t", tenantId).param("a", r.accountId()).param("p", r.productId()).param("n", name).param("o", objective).param("b", budget).param("c", integrations.credentials(account).getOrDefault("currency", "EGP"))
                    .param("s", Timestamp.from(start)).param("e", Timestamp.from(end)).param("pr", com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(params)).param("u", actor).query(UUID.class).single();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        try {
            String ext = build(account, id, name, objective, budget, start, end, params);
            jdbc.sql("UPDATE marketing.campaigns SET external_id = :e, status = 'PAUSED', last_error = NULL WHERE id = :i").param("e", ext).param("i", id).update();
            if (Boolean.TRUE.equals(r.launch())) setStatus(tenantId, actor, id, "ACTIVE");
        } catch (MetaGraph.MetaException e) {
            jdbc.sql("UPDATE marketing.campaigns SET status = 'FAILED', last_error = :m WHERE id = :i").param("m", IntegrationClip(e.getMessage())).param("i", id).update();
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "AD_REJECTED", e.getMessage());
        }
        audit.record(actor, tenantId, "AD_CAMPAIGN_CREATED", "marketing_campaign", id, null);
        return get(tenantId, id);
    }

    private static String IntegrationClip(String s) { return s == null ? null : s.length() > 480 ? s.substring(0, 480) : s; }

    /** Creates campaign, ad set, creative and ad (all paused); returns the Meta campaign id. A half-built campaign is deleted again. */
    @SuppressWarnings("unchecked")
    private String build(Map<String, Object> account, UUID id, String name, String objective, long budget, Instant start, Instant end, Map<String, Object> p) {
        Map<String, String> cred = integrations.credentials(account);
        String token = cred.get("userToken"), act = cred.get("adAccountId");
        String campaign = null;
        try {
            campaign = String.valueOf(graph.post(act + "/campaigns", token, Map.of("name", name, "objective", objective, "status", "PAUSED", "special_ad_categories", List.of())).get("id"));
            Map<String, Object> targeting = new LinkedHashMap<>();
            targeting.put("geo_locations", Map.of("countries", p.get("countries")));
            targeting.put("age_min", p.get("ageMin"));
            targeting.put("age_max", p.get("ageMax"));
            List<String> genders = (List<String>) p.get("genders");
            if (genders.size() == 1) targeting.put("genders", List.of("male".equals(genders.get(0)) ? 1 : 2));
            List<Map<String, Object>> interests = new ArrayList<>();
            for (String i : (List<String>) p.get("interests")) {
                try {
                    Map<String, Object> found = graph.get("search?type=adinterest&limit=1&q=" + URLEncoder.encode(i, StandardCharsets.UTF_8), token);
                    if (found.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) interests.add(Map.of("id", String.valueOf(m.get("id")), "name", String.valueOf(m.get("name"))));
                } catch (MetaGraph.MetaException ignored) { /* an interest Meta does not know is simply left out */ }
            }
            if (!interests.isEmpty()) targeting.put("flexible_spec", List.of(Map.of("interests", interests)));
            String goal = "OUTCOME_AWARENESS".equals(objective) ? "REACH" : "OUTCOME_ENGAGEMENT".equals(objective) ? "POST_ENGAGEMENT" : "LINK_CLICKS";
            Map<String, Object> adset = new LinkedHashMap<>();
            adset.put("name", name + " - audience"); adset.put("campaign_id", campaign); adset.put("daily_budget", budget); adset.put("billing_event", "IMPRESSIONS"); adset.put("optimization_goal", goal);
            adset.put("bid_strategy", "LOWEST_COST_WITHOUT_CAP"); adset.put("targeting", targeting); adset.put("start_time", start.toString()); adset.put("end_time", end.toString()); adset.put("status", "PAUSED");
            if ("OUTCOME_ENGAGEMENT".equals(objective)) adset.put("destination_type", "ON_POST");
            String adsetId = String.valueOf(graph.post(act + "/adsets", token, adset).get("id"));
            Map<String, Object> linkData = new LinkedHashMap<>();
            linkData.put("message", p.get("primaryText")); linkData.put("link", p.get("linkUrl")); linkData.put("name", p.get("headline")); linkData.put("picture", p.get("imageUrl"));
            linkData.put("call_to_action", Map.of("type", p.get("cta"), "value", Map.of("link", p.get("linkUrl"))));
            String creative = String.valueOf(graph.post(act + "/adcreatives", token, Map.of("name", name + " - creative", "object_story_spec", Map.of("page_id", p.get("pageId"), "link_data", linkData))).get("id"));
            graph.post(act + "/ads", token, Map.of("name", name + " - ad", "adset_id", adsetId, "creative", Map.of("creative_id", creative), "status", "PAUSED"));
            return campaign;
        } catch (MetaGraph.MetaException e) {
            if (campaign != null) { try { graph.post(campaign, token, Map.of("status", "DELETED")); } catch (RuntimeException ignored) { /* best effort */ } }
            throw e;
        }
    }

    // ---- running campaigns ----------------------------------------------------------------------------------------

    public Map<String, Object> setStatus(UUID tenantId, UUID actor, UUID id, String status) {
        var c = row(tenantId, id);
        if (c.get("external_id") == null) throw BusinessException.conflict("NOT_CREATED", "This campaign was not created on Meta");
        var account = integrations.row(tenantId, (UUID) c.get("account_id"));
        Map<String, String> cred = integrations.credentials(account);
        try {
            graph.post((String) c.get("external_id"), cred.get("userToken"), Map.of("status", "ACTIVE".equals(status) ? "ACTIVE" : "PAUSED"));
            // the ad set and ad were created paused: they are switched with the campaign
            for (String kind : List.of("adsets", "ads")) {
                Map<String, Object> children = graph.get(c.get("external_id") + "/" + kind + "?fields=id&limit=20", cred.get("userToken"));
                if (children.get("data") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) graph.post(String.valueOf(m.get("id")), cred.get("userToken"), Map.of("status", "ACTIVE".equals(status) ? "ACTIVE" : "PAUSED"));
            }
        } catch (MetaGraph.MetaException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "AD_REJECTED", e.getMessage());
        }
        jdbc.sql("UPDATE marketing.campaigns SET status = :s, last_error = NULL WHERE id = :i").param("s", "ACTIVE".equals(status) ? "ACTIVE" : "PAUSED").param("i", id).update();
        audit.record(actor, tenantId, "AD_CAMPAIGN_" + ("ACTIVE".equals(status) ? "LAUNCHED" : "PAUSED"), "marketing_campaign", id, null);
        return get(tenantId, id);
    }

    /** Reads the results (spend, reach, clicks...) from Meta. */
    public Map<String, Object> refresh(UUID tenantId, UUID id) {
        var c = row(tenantId, id);
        if (c.get("external_id") == null) return get(tenantId, id);
        var account = integrations.row(tenantId, (UUID) c.get("account_id"));
        Map<String, String> cred = integrations.credentials(account);
        try {
            Map<String, Object> st = graph.get(c.get("external_id") + "?fields=status,effective_status", cred.get("userToken"));
            String eff = String.valueOf(st.getOrDefault("effective_status", st.get("status")));
            String status = "ACTIVE".equals(eff) ? "ACTIVE" : "PAUSED".equals(eff) || "CAMPAIGN_PAUSED".equals(eff) ? "PAUSED" : "ARCHIVED".equals(eff) || "DELETED".equals(eff) ? "ENDED" : (String) c.get("status");
            if (c.get("ends_at") != null && ((Timestamp) c.get("ends_at")).toInstant().isBefore(Instant.now()) && "ACTIVE".equals(status)) status = "ENDED";
            Map<String, Object> ins = graph.get(c.get("external_id") + "/insights?fields=impressions,reach,clicks,spend,ctr,cpc,actions&date_preset=maximum", cred.get("userToken"));
            Map<String, Object> row = ins.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            jdbc.sql("UPDATE marketing.campaigns SET status = :s, insights = CAST(:i AS jsonb), last_synced_at = now(), last_error = NULL WHERE id = :id").param("s", status)
                    .param("i", com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(row)).param("id", id).update();
        } catch (MetaGraph.MetaException e) {
            jdbc.sql("UPDATE marketing.campaigns SET last_error = :m WHERE id = :i").param("m", IntegrationClip(e.getMessage())).param("i", id).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return get(tenantId, id);
    }

    @Scheduled(cron = "0 20 */3 * * *")
    public void refreshActive() {
        for (var c : jdbc.sql("SELECT id, tenant_id FROM marketing.campaigns WHERE status IN ('ACTIVE', 'PAUSED') AND external_id IS NOT NULL AND created_at > now() - interval '120 days' AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = marketing.campaigns.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) LIMIT 200").query().listOfRows()) {
            try { refresh((UUID) c.get("tenant_id"), (UUID) c.get("id")); } catch (RuntimeException e) { log.debug("Ad refresh failed: {}", e.getMessage()); }
        }
    }

    // ---- budget and copy variants (used by the autopilot, always inside the owner's guardrails) -------------------

    /** Changes the daily budget of the campaign's ad set(s) and the stored value. */
    public void setDailyBudget(UUID tenantId, UUID id, long minor) {
        var c = row(tenantId, id);
        if (c.get("external_id") == null) throw BusinessException.conflict("NOT_CREATED", "This campaign was not created on Meta");
        var account = integrations.row(tenantId, (UUID) c.get("account_id"));
        String token = integrations.credentials(account).get("userToken");
        try {
            Map<String, Object> sets = graph.get(c.get("external_id") + "/adsets?fields=id&limit=20", token);
            if (sets.get("data") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) graph.post(String.valueOf(m.get("id")), token, Map.of("daily_budget", minor));
        } catch (MetaGraph.MetaException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "AD_REJECTED", e.getMessage());
        }
        jdbc.sql("UPDATE marketing.campaigns SET daily_budget_minor = :b WHERE id = :i AND tenant_id = :t").param("b", minor).param("i", id).param("t", tenantId).update();
    }

    /** Adds one extra ad per text to the campaign's first ad set (same picture, link and audience), so the texts compete fairly. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> addVariants(UUID tenantId, UUID id, List<String[]> texts) {
        var c = jdbc.sql("SELECT id, account_id, external_id, status, name, params::text AS params FROM marketing.campaigns WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Campaign not found"));
        if (c.get("external_id") == null) throw BusinessException.conflict("NOT_CREATED", "This campaign was not created on Meta");
        var account = integrations.row(tenantId, (UUID) c.get("account_id"));
        Map<String, String> cred = integrations.credentials(account);
        String token = cred.get("userToken"), act = cred.get("adAccountId");
        Map<String, Object> p = (Map<String, Object>) Rows.json(c.get("params"));
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, Object> sets = graph.get(c.get("external_id") + "/adsets?fields=id&limit=1", token);
            String adset = sets.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : null;
            if (adset == null) throw BusinessException.conflict("NOT_CREATED", "The campaign has no ad set");
            String adStatus = "ACTIVE".equals(c.get("status")) ? "ACTIVE" : "PAUSED";
            for (String[] t : texts) {
                Map<String, Object> linkData = new LinkedHashMap<>();
                linkData.put("message", t[1]); linkData.put("link", p.get("linkUrl")); linkData.put("name", t[0]); linkData.put("picture", p.get("imageUrl"));
                linkData.put("call_to_action", Map.of("type", p.get("cta"), "value", Map.of("link", p.get("linkUrl"))));
                String creative = String.valueOf(graph.post(act + "/adcreatives", token, Map.of("name", c.get("name") + " - variant", "object_story_spec", Map.of("page_id", p.get("pageId"), "link_data", linkData))).get("id"));
                String ad = String.valueOf(graph.post(act + "/ads", token, Map.of("name", c.get("name") + " - " + t[0], "adset_id", adset, "creative", Map.of("creative_id", creative), "status", adStatus)).get("id"));
                UUID vid = jdbc.sql("INSERT INTO marketing.copy_variants (tenant_id, campaign_id, headline, primary_text, ad_external_id) VALUES (:t, :c, :h, :p, :a) RETURNING id")
                        .param("t", tenantId).param("c", id).param("h", t[0]).param("p", t[1]).param("a", ad).query(UUID.class).single();
                out.add(Map.of("id", vid, "headline", t[0], "primaryText", t[1], "adId", ad));
            }
        } catch (MetaGraph.MetaException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "AD_REJECTED", e.getMessage());
        }
        return out;
    }

    /** Pauses or resumes one variant's ad on Meta. */
    public void setAdStatus(UUID tenantId, UUID campaignId, String adExternalId, boolean active) {
        var c = row(tenantId, campaignId);
        String token = integrations.credentials(integrations.row(tenantId, (UUID) c.get("account_id"))).get("userToken");
        try { graph.post(adExternalId, token, Map.of("status", active ? "ACTIVE" : "PAUSED")); } catch (MetaGraph.MetaException e) { throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "AD_REJECTED", e.getMessage()); }
    }

    /** Reads each variant's own results from Meta into its row. */
    @SuppressWarnings("unchecked")
    public void refreshVariants(UUID tenantId, UUID campaignId) {
        var c = row(tenantId, campaignId);
        String token = integrations.credentials(integrations.row(tenantId, (UUID) c.get("account_id"))).get("userToken");
        for (var v : jdbc.sql("SELECT id, ad_external_id FROM marketing.copy_variants WHERE campaign_id = :c AND ad_external_id IS NOT NULL").param("c", campaignId).query().listOfRows()) {
            try {
                Map<String, Object> ins = graph.get(v.get("ad_external_id") + "/insights?fields=impressions,clicks,spend,cpc&date_preset=maximum", token);
                Map<String, Object> r = ins.get("data") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                jdbc.sql("UPDATE marketing.copy_variants SET insights = CAST(:i AS jsonb) WHERE id = :v").param("i", com.fasterxml.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(r)).param("v", v.get("id")).update();
            } catch (MetaGraph.MetaException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.debug("Variant insights failed: {}", e.getMessage());
            }
        }
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    private Map<String, Object> row(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT id, account_id, external_id, status, ends_at FROM marketing.campaigns WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Campaign not found"));
    }

    private static final String SELECT = "SELECT c.id, c.account_id, c.product_id, p.name AS product_name, c.external_id, c.name, c.objective, c.status, c.daily_budget_minor, c.currency, c.starts_at, c.ends_at, c.params::text AS params, c.insights::text AS insights, c.last_error, c.last_synced_at, c.created_at "
            + "FROM marketing.campaigns c LEFT JOIN commerce.products p ON p.id = c.product_id";

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
        m.put("params", Rows.json(r.get("params")));
        m.put("insights", Rows.json(r.get("insights")));
        return m;
    }

    public Map<String, Object> get(UUID tenantId, UUID id) {
        return view(jdbc.sql(SELECT + " WHERE c.id = :i AND c.tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Campaign not found")));
    }

    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.sql(SELECT + " WHERE c.tenant_id = :t ORDER BY c.created_at DESC LIMIT 100").param("t", tenantId).query().listOfRows().stream().map(this::view).toList();
    }
}
