package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.chat.AiProvider;
import com.platform.marketing.AiTextHelper;
import com.platform.shared.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * "Describe your shop and we start it": the AI proposes a short shop description and a first set of products from a few lines the owner writes. Everything it makes is a DRAFT
 * product (hidden from shoppers, no stock, no category) for the owner to review, price and publish; existing text and products are never overwritten.
 */
@Service
public class StoreBuilderService {
    private static final Logger log = LoggerFactory.getLogger(StoreBuilderService.class);

    private final AiProvider ai;
    private final CatalogService catalog;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public StoreBuilderService(AiProvider ai, CatalogService catalog, JdbcClient jdbc, AuditService audit) {
        this.ai = ai;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(UUID t, UUID actor, String description, String language) {
        String d = description == null ? "" : description.trim();
        if (d.length() < 15 || d.length() > 1500) throw BusinessException.badRequest("VALIDATION_ERROR", "Describe your shop in 15 to 1500 characters");
        if (!ai.available()) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "The AI assistant is not set up yet");
        boolean ar = !"en".equalsIgnoreCase(language);
        String cur = jdbc.sql("SELECT default_currency FROM core.tenants WHERE id = :t").param("t", t).query(String.class).single();
        String system = "You help a small online shop owner in the Middle East start their shop. From the owner's description write: a one-sentence shop description (max 120 chars) and an 'about' paragraph (max 400 chars), and up to 8 realistic starter products. "
                + "Write in " + (ar ? "Arabic (friendly Egyptian-neutral wording)" : "English") + ". Prices are integers in " + cur + " units (not cents) and must be plausible for this market; do not invent brands, certifications or claims. "
                + "Reply with ONLY a JSON object {\"shortDescription\": string, \"about\": string, \"template\": one of original|atelier|souq|midnight|bloom|oud, \"products\": [{\"name\": string max 80, \"description\": string max 300, \"price\": integer}]}.";
        Map<String, Object> m;
        try { m = AiTextHelper.json(ai.next(system, List.of(AiProvider.Turn.user(d)), List.of()).text()); }
        catch (AiProvider.AiUnavailableException e) { throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "The AI assistant could not be reached, try again"); }
        catch (RuntimeException e) { throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY, "AI_BAD_ANSWER", "The AI answer could not be read, try again"); }
        Map<String, Object> out = new LinkedHashMap<>();
        String sd = m.get("shortDescription") instanceof String s ? clip(s.trim(), 120) : "", about = m.get("about") instanceof String s ? clip(s.trim(), 400) : "";
        boolean wrote = false;
        if (!sd.isEmpty() && jdbc.sql("UPDATE commerce.store_profiles SET short_description = :s WHERE tenant_id = :t AND (short_description IS NULL OR short_description = '')").param("s", sd).param("t", tenant(t)).update() > 0) wrote = true;
        if (!about.isEmpty() && jdbc.sql("UPDATE commerce.store_profiles SET about = :s WHERE tenant_id = :t AND (about IS NULL OR about = '')").param("s", about).param("t", tenant(t)).update() > 0) wrote = true;
        out.put("shortDescription", sd);
        out.put("about", about);
        out.put("descriptionSaved", wrote);
        String tpl = m.get("template") instanceof String s ? s : "original";
        out.put("suggestedTemplate", List.of("original", "atelier", "souq", "midnight", "bloom", "oud").contains(tpl) ? tpl : "original");
        List<Map<String, Object>> created = new ArrayList<>();
        if (m.get("products") instanceof List<?> l) {
            for (Object o : l) {
                if (created.size() >= 8 || !(o instanceof Map<?, ?> p) || !(p.get("name") instanceof String name) || name.trim().length() < 2) continue;
                long price = p.get("price") instanceof Number n ? Math.max(1, Math.min(n.longValue(), 10_000_000L)) : 100;
                try {
                    var r = catalog.createProduct(t, actor, new CatalogService.ProductReq(clip(name.trim(), 80), p.get("description") instanceof String s ? clip(s.trim(), 300) : "", null, null, null, null, "ALL", null, "DRAFT", List.of(),
                            List.of(new CatalogService.VariantReq("AI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), price * 100, null, null, null, Map.of(), List.of())), List.of()));
                    created.add(Map.of("id", r.get("id"), "name", name.trim(), "priceMinor", price * 100));
                } catch (BusinessException e) {
                    log.info("Builder product skipped: {}", e.getMessage());
                    if ("PLAN_LIMIT_REACHED".equals(e.getCode())) break;
                }
            }
        }
        out.put("products", created);
        audit.record(actor, t, "STORE_BUILDER_USED", "tenant", t, "{\"products\":" + created.size() + "}");
        return out;
    }

    private UUID tenant(UUID t) { return t; }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
