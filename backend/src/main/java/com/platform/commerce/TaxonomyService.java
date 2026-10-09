package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.shared.BusinessException;
import com.platform.shared.Rows;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform's standard product categories (commerce.taxonomy), the standard colour/size values, and the owner flow around them:
 * browse/search to pick a category, suggest a missing one. Shops can never create or rename categories; the platform team does.
 */
@Service
public class TaxonomyService {
    static final Set<String> AUDIENCES = Set.of("MEN", "WOMEN", "BOYS", "GIRLS", "BABY", "ALL");
    static final Set<String> CONDITIONS = Set.of("NEW", "USED", "REFURBISHED");

    private final JdbcClient jdbc;
    private final AuditService audit;

    public TaxonomyService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String name(Map<String, Object> m, String lang, String arKey, String enKey) { return "en".equals(lang) ? (String) m.get(enKey) : (String) m.get(arKey); }

    // ---- shopper side ---------------------------------------------------------------------------------------------

    /** The category tree of one shop: only categories that have products (own or below), each with its product count. Same shape the storefront and app already use. */
    public List<Map<String, Object>> publicTree(UUID tenantId, String lang) {
        var rows = jdbc.sql("""
                SELECT t.id, t.parent_id, t.slug, t.name_ar, t.name_en, t.icon, t.level, t.sort_order, t.applies_audience,
                  (SELECT count(*) FROM commerce.products p JOIN commerce.taxonomy c ON c.id = p.taxonomy_id
                    WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND c.path LIKE t.path || '%') AS product_count
                FROM commerce.taxonomy t WHERE t.is_active ORDER BY t.sort_order
                """).param("t", tenantId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            if (((Number) r.get("product_count")).longValue() == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            m.put("name", name(r, lang, "name_ar", "name_en"));
            m.put("imageFileId", null);
            out.add(m);
        }
        return out;
    }

    // ---- owner side: pick a category ----------------------------------------------------------------------------------

    private static final String NODE_SELECT = """
            SELECT t.id, t.parent_id, t.slug, t.name_ar, t.name_en, t.icon, t.level, t.size_scales, t.applies_audience, t.is_hidden,
              EXISTS (SELECT 1 FROM commerce.taxonomy c WHERE c.parent_id = t.id AND c.is_active AND NOT c.is_hidden) AS has_children,
              (SELECT string_agg(a.name_ar, ' › ' ORDER BY a.level) FROM commerce.taxonomy a WHERE t.path LIKE a.path || '%') AS crumb_ar,
              (SELECT string_agg(a.name_en, ' › ' ORDER BY a.level) FROM commerce.taxonomy a WHERE t.path LIKE a.path || '%') AS crumb_en
            FROM commerce.taxonomy t
            """;

    private List<Map<String, Object>> nodes(List<Map<String, Object>> rows, String lang) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(Rows.camel(r));
            m.put("name", name(r, lang, "name_ar", "name_en"));
            m.put("breadcrumb", "en".equals(lang) ? r.get("crumb_en") : r.get("crumb_ar"));
            m.put("sizeScales", ((String) r.get("size_scales")).isEmpty() ? List.of() : List.of(((String) r.get("size_scales")).split(",")));
            m.remove("crumbAr");
            m.remove("crumbEn");
            out.add(m);
        }
        return out;
    }

    /** Browse: the top level (parent = null) or the children of one category. Hidden/inactive ones are never offered. */
    public List<Map<String, Object>> children(Integer parentId, String lang) {
        return nodes(jdbc.sql(NODE_SELECT + " WHERE t.is_active AND NOT t.is_hidden AND t.parent_id IS NOT DISTINCT FROM CAST(:p AS int) ORDER BY t.sort_order").param("p", parentId).query().listOfRows(), lang);
    }

    /** Search by Arabic or English words: letter variants and diacritics are ignored. Only categories a product can be filed under (level 2 and below). */
    public List<Map<String, Object>> search(String q, String lang, int limit) {
        List<String> tokens = new ArrayList<>();
        for (String w : StoreSearchService.norm(q).split("\\s+")) if (!w.isBlank() && tokens.size() < 3) tokens.add(w);
        if (tokens.isEmpty()) return List.of();
        var st = jdbc.sql(NODE_SELECT + """
                 WHERE t.is_active AND NOT t.is_hidden AND t.level >= 2
                   AND (CAST(:q1 AS varchar) IS NULL OR t.search_text ILIKE '%' || CAST(:q1 AS varchar) || '%')
                   AND (CAST(:q2 AS varchar) IS NULL OR t.search_text ILIKE '%' || CAST(:q2 AS varchar) || '%')
                   AND (CAST(:q3 AS varchar) IS NULL OR t.search_text ILIKE '%' || CAST(:q3 AS varchar) || '%')
                 ORDER BY (commerce.norm_ar(t.name_ar) = :full OR lower(t.name_en) = :full) DESC, t.level DESC, t.sort_order LIMIT :lim
                """).param("q1", tokens.get(0)).param("q2", tokens.size() > 1 ? tokens.get(1) : null).param("q3", tokens.size() > 2 ? tokens.get(2) : null)
                .param("full", String.join(" ", tokens)).param("lim", Math.min(Math.max(limit, 1), 30));
        return nodes(st.query().listOfRows(), lang);
    }

    /** Categories that fit the shop's own business types (chosen at sign-up): the quick list shown first in the picker. */
    public List<Map<String, Object>> suggested(UUID tenantId, String lang) {
        return nodes(jdbc.sql(NODE_SELECT + """
                 WHERE t.is_active AND NOT t.is_hidden AND t.level >= 2 AND (
                   t.parent_id IN (SELECT m.taxonomy_id FROM commerce.business_type_taxonomy m
                                   JOIN commerce.store_categories sc ON sc.code = m.business_code
                                   JOIN commerce.store_category_links l ON l.category_id = sc.id AND l.tenant_id = :t)
                   OR (t.id IN (SELECT m.taxonomy_id FROM commerce.business_type_taxonomy m
                                JOIN commerce.store_categories sc ON sc.code = m.business_code
                                JOIN commerce.store_category_links l ON l.category_id = sc.id AND l.tenant_id = :t)
                       AND NOT EXISTS (SELECT 1 FROM commerce.taxonomy c WHERE c.parent_id = t.id AND c.is_active AND NOT c.is_hidden)))
                 ORDER BY t.sort_order LIMIT 80
                """).param("t", tenantId).query().listOfRows(), lang);
    }

    /** Resolves what the client sent (id or slug) to an active, pickable category; null when none was given. */
    public Map<String, Object> pick(Integer id, String slug, boolean allowHidden) {
        if (id == null && (slug == null || slug.isBlank())) return null;
        var row = jdbc.sql("SELECT id, level, size_scales, applies_audience, is_hidden, is_active FROM commerce.taxonomy WHERE (CAST(:i AS int) IS NOT NULL AND id = CAST(:i AS int)) OR (CAST(:s AS varchar) IS NOT NULL AND slug = CAST(:s AS varchar))")
                .param("i", id).param("s", slug == null || slug.isBlank() ? null : slug.trim()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.badRequest("CATEGORY_INVALID", "Choose a category from the list"));
        boolean ok = Boolean.TRUE.equals(row.get("is_active")) && (allowHidden || (!Boolean.TRUE.equals(row.get("is_hidden")) && ((Number) row.get("level")).intValue() >= 2));
        if (!ok) throw BusinessException.badRequest("CATEGORY_INVALID", "Choose a category from the list");
        return row;
    }

    /** Standard colour and size values (with hex swatches / scales) for the product form. */
    public Map<String, Object> attributes(String lang) {
        var rows = jdbc.sql("SELECT attr, scale, code, name_ar, name_en, hex FROM commerce.attribute_values ORDER BY attr, scale, sort_order").query().listOfRows();
        List<Map<String, Object>> colors = new ArrayList<>();
        Map<String, List<Map<String, Object>>> sizes = new LinkedHashMap<>();
        for (var r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.get("code"));
            m.put("nameAr", r.get("name_ar"));
            m.put("nameEn", r.get("name_en"));
            m.put("name", name(r, lang, "name_ar", "name_en"));
            if ("COLOR".equals(r.get("attr"))) { m.put("hex", r.get("hex")); colors.add(m); }
            else sizes.computeIfAbsent((String) r.get("scale"), k -> new ArrayList<>()).add(m);
        }
        return Map.of("colors", colors, "sizes", sizes, "audiences", List.of("MEN", "WOMEN", "BOYS", "GIRLS", "BABY", "ALL"), "conditions", List.of("NEW", "USED", "REFURBISHED"));
    }

    // ---- standard option values (used by CatalogService when a product is saved) ------------------------------------------

    /** Maps what a client typed/picked (code, Arabic or English name) to the standard value; null when it is not a standard value. */
    Map<String, Object> standardValue(String attr, String scale, String value) {
        if (value == null) return null;
        String v = value.trim();
        String n = StoreSearchService.norm(v);
        return jdbc.sql("SELECT code, name_ar, name_en FROM commerce.attribute_values WHERE attr = :a AND scale = :s AND (code = :v OR commerce.norm_ar(name_ar) = :n OR lower(name_en) = :n) LIMIT 1")
                .param("a", attr).param("s", scale).param("v", v.toLowerCase(Locale.ROOT)).param("n", n).query().listOfRows().stream().findFirst().orElse(null);
    }

    // ---- suggestions from owners ------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> request(UUID tenantId, String name, Integer parentId, String note) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 120) throw BusinessException.badRequest("VALIDATION_ERROR", "Write the category name (2-120 characters)");
        if (jdbc.sql("SELECT count(*) FROM commerce.taxonomy_requests WHERE tenant_id = :t AND status = 'PENDING'").param("t", tenantId).query(Long.class).single() >= 5)
            throw BusinessException.badRequest("TOO_MANY_REQUESTS", "You already have 5 suggestions waiting for review");
        // an existing category with this name is the answer, not a new request
        var same = search(n, "ar", 3).stream().filter(x -> StoreSearchService.norm((String) x.get("nameAr")).equals(StoreSearchService.norm(n)) || ((String) x.get("nameEn")).equalsIgnoreCase(n)).findFirst();
        if (same.isPresent()) throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "CATEGORY_EXISTS", "This category already exists: " + same.get().get("breadcrumb"));
        UUID id = jdbc.sql("INSERT INTO commerce.taxonomy_requests (tenant_id, name, parent_id, note) VALUES (:t, :n, :p, :no) RETURNING id")
                .param("t", tenantId).param("n", n).param("p", parentId).param("no", note == null ? "" : note.trim().substring(0, Math.min(500, note.trim().length()))).query(UUID.class).single();
        audit.record(null, tenantId, "CATEGORY_REQUESTED", "taxonomy_request", id, null);
        return Map.of("id", id, "status", "PENDING");
    }

    public List<Map<String, Object>> myRequests(UUID tenantId) {
        return Rows.camel(jdbc.sql("SELECT id, name, status, admin_note, created_at FROM commerce.taxonomy_requests WHERE tenant_id = :t ORDER BY created_at DESC LIMIT 20").param("t", tenantId).query().listOfRows());
    }

    // ---- platform team --------------------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> adminList() {
        return Rows.camel(jdbc.sql("""
                SELECT t.id, t.parent_id, t.slug, t.name_ar, t.name_en, t.level, t.icon, t.sort_order, t.is_active, t.is_hidden, t.applies_audience, t.size_scales,
                  (SELECT count(*) FROM commerce.products p WHERE p.taxonomy_id = t.id) AS own_products
                FROM commerce.taxonomy t ORDER BY t.sort_order, t.id
                """).query().listOfRows());
    }

    @Transactional
    public Map<String, Object> adminUpdate(UUID actor, int id, Map<String, Object> f) {
        var cur = jdbc.sql("SELECT id FROM commerce.taxonomy WHERE id = :i").param("i", id).query().listOfRows();
        if (cur.isEmpty()) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
        String ar = f.get("nameAr") instanceof String s && !s.isBlank() ? s.trim() : null, en = f.get("nameEn") instanceof String s && !s.isBlank() ? s.trim() : null;
        jdbc.sql("UPDATE commerce.taxonomy SET name_ar = coalesce(:ar, name_ar), name_en = coalesce(:en, name_en), is_active = coalesce(:ac, is_active), is_hidden = coalesce(:hi, is_hidden), sort_order = coalesce(:so, sort_order) WHERE id = :i")
                .param("ar", ar).param("en", en).param("ac", f.get("isActive") instanceof Boolean b ? b : null).param("hi", f.get("isHidden") instanceof Boolean b ? b : null)
                .param("so", f.get("sortOrder") instanceof Number n ? n.intValue() : null).param("i", id).update();
        jdbc.sql("UPDATE commerce.taxonomy SET search_text = commerce.norm_ar(name_ar || ' ' || name_en) WHERE id = :i").param("i", id).update();
        audit.record(actor, null, "TAXONOMY_UPDATED", "taxonomy", null, "{\"id\":" + id + "}");
        return adminList().stream().filter(m -> Integer.valueOf(id).equals(m.get("id"))).findFirst().orElseThrow();
    }

    /** New category under `parentId` (null = top level). The slug is made from the English name and kept unique. */
    @Transactional
    public Map<String, Object> adminCreate(UUID actor, Integer parentId, String nameAr, String nameEn) {
        if (nameAr == null || nameAr.isBlank() || nameEn == null || nameEn.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Arabic and English names are required");
        String parentPath = "/";
        int level = 1;
        if (parentId != null) {
            var p = jdbc.sql("SELECT path, level FROM commerce.taxonomy WHERE id = :i").param("i", parentId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.badRequest("CATEGORY_INVALID", "Unknown parent"));
            parentPath = (String) p.get("path");
            level = ((Number) p.get("level")).intValue() + 1;
            if (level > 3) throw BusinessException.badRequest("CATEGORY_TOO_DEEP", "Categories go up to 3 levels");
        }
        int id = jdbc.sql("SELECT coalesce(max(id), 0) + 1 FROM commerce.taxonomy").query(Integer.class).single();
        String base = Normalizer.normalize(nameEn.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "category";
        String slug = base;
        for (int i = 2; jdbc.sql("SELECT count(*) FROM commerce.taxonomy WHERE slug = :s").param("s", slug).query(Long.class).single() > 0; i++) slug = base + "-" + i;
        int sort = jdbc.sql("SELECT coalesce(max(sort_order), 0) + 1 FROM commerce.taxonomy").query(Integer.class).single();
        // inherit flags from the parent so a new sub-category behaves like its siblings (size scales, "For" filter)
        jdbc.sql("""
                INSERT INTO commerce.taxonomy (id, parent_id, level, slug, name_ar, name_en, path, icon, size_scales, applies_audience, sort_order, search_text)
                SELECT :id, :p, :lv, :slug, :ar, :en, :path, NULL, coalesce(par.size_scales, ''), coalesce(par.applies_audience, FALSE), :so, commerce.norm_ar(:ar || ' ' || :en)
                FROM (SELECT 1) x LEFT JOIN commerce.taxonomy par ON par.id = :p
                """).param("id", id).param("p", parentId).param("lv", level).param("slug", slug).param("ar", nameAr.trim()).param("en", nameEn.trim()).param("path", parentPath + id + "/").param("so", sort).update();
        audit.record(actor, null, "TAXONOMY_CREATED", "taxonomy", null, "{\"id\":" + id + "}");
        return adminList().stream().filter(m -> Integer.valueOf(id).equals(m.get("id"))).findFirst().orElseThrow();
    }

    public List<Map<String, Object>> adminRequests(String status) {
        return Rows.camel(jdbc.sql("""
                SELECT r.id, r.tenant_id, tn.name AS shop_name, r.name, r.parent_id, r.note, r.status, r.admin_note, r.taxonomy_id, r.created_at
                FROM commerce.taxonomy_requests r JOIN core.tenants tn ON tn.id = r.tenant_id
                WHERE (CAST(:s AS varchar) IS NULL OR r.status = CAST(:s AS varchar)) ORDER BY r.created_at DESC LIMIT 200
                """).param("s", status == null || status.isBlank() ? null : status).query().listOfRows());
    }

    /** approve: creates the category (names given by the admin) and links the request to it; reject: closes it with a note. */
    @Transactional
    public Map<String, Object> adminDecide(UUID actor, UUID requestId, boolean approve, Integer parentId, String nameAr, String nameEn, String note) {
        var r = jdbc.sql("SELECT status FROM commerce.taxonomy_requests WHERE id = :i FOR UPDATE").param("i", requestId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found"));
        if (!"PENDING".equals(r.get("status"))) throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "ALREADY_DECIDED", "This suggestion was already handled");
        Integer created = null;
        if (approve) created = (Integer) adminCreate(actor, parentId, nameAr, nameEn).get("id");
        jdbc.sql("UPDATE commerce.taxonomy_requests SET status = :s, admin_note = :n, taxonomy_id = :t, decided_at = now() WHERE id = :i")
                .param("s", approve ? "APPROVED" : "REJECTED").param("n", note == null ? "" : note.trim()).param("t", created).param("i", requestId).update();
        Map<String, Object> res = new LinkedHashMap<>(); res.put("ok", true); res.put("taxonomyId", created); return res;
    }
}
