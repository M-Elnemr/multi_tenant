package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.chat.AiProvider;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** A second language for product names and descriptions (English next to the Arabic original), written by hand or drafted by the AI for the owner to check. Shoppers who read English see it where it exists. */
@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    public static final Set<String> LOCALES = Set.of("en");

    private final JdbcClient jdbc;
    private final AiProvider ai;
    private final AuditService audit;

    private final com.platform.chat.AiUsageService aiUsage;

    public TranslationService(JdbcClient jdbc, AiProvider ai, AuditService audit, com.platform.chat.AiUsageService aiUsage) {
        this.aiUsage = aiUsage;
        this.jdbc = jdbc;
        this.ai = ai;
        this.audit = audit;
    }

    public Map<String, Object> save(UUID t, UUID actor, UUID productId, String locale, String name, String description) {
        if (!LOCALES.contains(locale)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown language");
        if (name == null || name.trim().length() < 2 || name.trim().length() > 250) throw BusinessException.badRequest("VALIDATION_ERROR", "The name must be 2 to 250 characters");
        if (jdbc.sql("SELECT count(*) FROM commerce.products WHERE id = :p AND tenant_id = :t").param("p", productId).param("t", t).query(Long.class).single() == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found");
        jdbc.sql("INSERT INTO commerce.product_translations (product_id, tenant_id, locale, name, description) VALUES (:p, :t, :l, :n, :d) ON CONFLICT (product_id, locale) DO UPDATE SET name = :n, description = :d")
                .param("p", productId).param("t", t).param("l", locale).param("n", name.trim()).param("d", description == null ? "" : description.trim()).update();
        audit.record(actor, t, "PRODUCT_TRANSLATED", "product", productId, null);
        return get(t, productId, locale);
    }

    public Map<String, Object> get(UUID t, UUID productId, String locale) {
        return jdbc.sql("SELECT name, description FROM commerce.product_translations WHERE product_id = :p AND tenant_id = :t AND locale = :l").param("p", productId).param("t", t).param("l", locale).query().listOfRows().stream().findFirst().map(Rows::camel).orElse(Map.of());
    }

    /** Products with their Arabic text and whether English exists, for the owner's translation screen. */
    public List<Map<String, Object>> overview(UUID t) {
        return Rows.camel(jdbc.sql("SELECT p.id, p.name, p.description, tr.name AS name_en, tr.description AS description_en FROM commerce.products p LEFT JOIN commerce.product_translations tr ON tr.product_id = p.id AND tr.locale = 'en' WHERE p.tenant_id = :t AND p.status <> 'ARCHIVED' ORDER BY (tr.name IS NULL) DESC, p.name LIMIT 300").param("t", t).query().listOfRows());
    }

    /** Asks the AI for an English draft of the products that have none; the drafts are saved as ordinary translations the owner can edit. Returns how many were drafted. */
    public int draftMissing(UUID t, UUID actor, int max) {
        if (!ai.available()) throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "The AI assistant is not set up yet");
        var todo = jdbc.sql("SELECT p.id, p.name, p.description FROM commerce.products p WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM commerce.product_translations x WHERE x.product_id = p.id AND x.locale = 'en') ORDER BY p.created_at DESC LIMIT :m")
                .param("t", t).param("m", Math.max(1, Math.min(max, 20))).query().listOfRows();
        int done = 0;
        for (var p : todo) {
            try {
                String system = "Translate an online shop product from Arabic to natural English for shoppers. Keep brand names, numbers and sizes. Do not add claims that are not in the text. Reply with ONLY a JSON object {\"name\": string max 120 chars, \"description\": string}.";
                String facts = "Name: " + p.get("name") + "\nDescription: " + clip((String) p.get("description"), 1500);
                var turn = ai.next(system, List.of(AiProvider.Turn.user(facts)), List.of());
                aiUsage.recordCall(t, "TRANSLATION", turn);
                var m = com.platform.marketing.AiTextHelper.json(turn.text());
                String name = m.get("name") instanceof String s ? s.trim() : "";
                if (name.length() < 2) continue;
                String desc = m.get("description") instanceof String s ? s.trim() : "";
                jdbc.sql("INSERT INTO commerce.product_translations (product_id, tenant_id, locale, name, description) VALUES (:p, :t, 'en', :n, :d) ON CONFLICT DO NOTHING").param("p", p.get("id")).param("t", t).param("n", clip(name, 250)).param("d", desc).update();
                done++;
            } catch (RuntimeException e) {
                log.info("Translation draft skipped: {}", e.getMessage());
            }
        }
        audit.record(actor, t, "PRODUCTS_TRANSLATED_BY_AI", "product", null, "{\"count\":" + done + "}");
        return done;
    }

    private static String clip(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    /** Replaces name (and description when present) of the maps that carry a product {@code id}, when English was asked for and a translation exists. */
    public void overlay(UUID t, String lang, List<Map<String, Object>> rows) {
        if (!"en".equalsIgnoreCase(lang) || rows == null || rows.isEmpty()) return;
        List<UUID> ids = new ArrayList<>();
        for (var r : rows) if (r.get("id") instanceof UUID u) ids.add(u);
        if (ids.isEmpty()) return;
        Map<UUID, Map<String, Object>> byId = new HashMap<>();
        for (var tr : jdbc.sql("SELECT product_id, name, description FROM commerce.product_translations WHERE tenant_id = :t AND locale = 'en' AND product_id IN (:ids)").param("t", t).param("ids", ids).query().listOfRows()) byId.put((UUID) tr.get("product_id"), tr);
        for (var r : rows) {
            var tr = r.get("id") instanceof UUID u ? byId.get(u) : null;
            if (tr == null) continue;
            r.put("name", tr.get("name"));
            if (r.containsKey("description") && tr.get("description") != null && !((String) tr.get("description")).isBlank()) r.put("description", tr.get("description"));
        }
    }

    public void overlayOne(UUID t, String lang, Map<String, Object> row) { overlay(t, lang, new ArrayList<>(List.of(row))); }
}
