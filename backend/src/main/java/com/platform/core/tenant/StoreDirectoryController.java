package com.platform.core.tenant;

import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public directory of shops for the client app: pick a shop from a list (search + filter by specialty and city) instead of typing an address. */
@RestController
@RequestMapping("/api/v1/tenant/stores")
public class StoreDirectoryController {
    private static final String LIVE = "t.tenant_type = 'STORE' AND t.status IN ('TRIAL','ACTIVE','PAST_DUE')";
    private final JdbcClient jdbc;

    public StoreDirectoryController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String category, @RequestParam(required = false) String governorate,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        Page pg = new Page(Math.max(1, page), Math.min(Math.max(1, pageSize), 50));
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = LIVE + """
                 AND EXISTS (SELECT 1 FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary AND d.is_verified)
                 AND (CAST(:like AS varchar) IS NULL OR t.name ILIKE CAST(:like AS varchar) OR t.slug ILIKE CAST(:like AS varchar))
                 AND (CAST(:cat AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.store_category_links l JOIN commerce.store_categories c ON c.id = l.category_id WHERE l.tenant_id = t.id AND c.code = CAST(:cat AS varchar)))
                 AND (CAST(:gov AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.branches br WHERE br.tenant_id = t.id AND br.is_active AND br.governorate_code = CAST(:gov AS varchar)))
                """;
        long total = jdbc.sql("SELECT count(*) FROM core.tenants t WHERE " + where).param("like", like).param("cat", blank(category)).param("gov", blank(governorate)).query(Long.class).single();
        var rows = Rows.camel(jdbc.sql("""
                SELECT t.id, t.slug, t.name, b.primary_color, b.logo_file_id,
                       (SELECT d.host FROM core.tenant_domains d WHERE d.tenant_id = t.id AND d.is_primary AND d.is_verified) AS host
                FROM core.tenants t LEFT JOIN core.branding b ON b.tenant_id = t.id WHERE
                """ + where + " ORDER BY t.name LIMIT :lim OFFSET :off")
                .param("like", like).param("cat", blank(category)).param("gov", blank(governorate)).param("lim", pg.pageSize()).param("off", pg.offset()).query().listOfRows());
        List<UUID> ids = rows.stream().map(r -> (UUID) r.get("id")).toList();
        Map<UUID, List<Map<String, Object>>> cats = new LinkedHashMap<>();
        Map<UUID, List<Map<String, Object>>> cities = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (var r : jdbc.sql("SELECT l.tenant_id, c.code, c.name_ar, c.name_en FROM commerce.store_category_links l JOIN commerce.store_categories c ON c.id = l.category_id WHERE l.tenant_id IN (:ids) ORDER BY c.sort_order").param("ids", ids).query().listOfRows())
                cats.computeIfAbsent((UUID) r.get("tenant_id"), k -> new java.util.ArrayList<>()).add(Map.of("code", r.get("code"), "nameAr", r.get("name_ar"), "nameEn", r.get("name_en")));
            for (var r : jdbc.sql("SELECT DISTINCT tenant_id, governorate_code, city FROM commerce.branches WHERE tenant_id IN (:ids) AND is_active AND governorate_code <> ''").param("ids", ids).query().listOfRows())
                cities.computeIfAbsent((UUID) r.get("tenant_id"), k -> new java.util.ArrayList<>()).add(Map.of("governorateCode", r.get("governorate_code"), "city", r.get("city") == null ? "" : r.get("city")));
        }
        List<Map<String, Object>> data = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String host = (String) r.get("host");
            m.put("id", r.get("id"));
            m.put("slug", r.get("slug"));
            m.put("name", r.get("name"));
            m.put("host", host);
            m.put("baseUrl", "https://" + host);
            m.put("primaryColor", r.get("primaryColor"));
            m.put("logoUrl", r.get("logoFileId") == null ? null : "https://" + host + "/api/v1/files/" + r.get("logoFileId") + "/content?variant=thumb");
            m.put("categories", cats.getOrDefault((UUID) r.get("id"), List.of()));
            m.put("cities", cities.getOrDefault((UUID) r.get("id"), List.of()));
            return m;
        }).toList();
        return pg.wrap(data, total);
    }

    /** Only the specialties and cities that at least one live shop actually uses, so the filters never lead to an empty list. */
    @GetMapping("/filters")
    public Map<String, Object> filters() {
        var cats = jdbc.sql("""
                SELECT DISTINCT c.code, c.name_ar, c.name_en, c.sort_order FROM commerce.store_category_links l JOIN commerce.store_categories c ON c.id = l.category_id
                JOIN core.tenants t ON t.id = l.tenant_id WHERE %s ORDER BY c.sort_order, c.name_en
                """.formatted(LIVE)).query().listOfRows().stream().map(r -> Map.of("code", r.get("code"), "nameAr", r.get("name_ar"), "nameEn", r.get("name_en"))).toList();
        var govs = jdbc.sql("""
                SELECT DISTINCT br.governorate_code FROM commerce.branches br JOIN core.tenants t ON t.id = br.tenant_id
                WHERE %s AND br.is_active AND br.governorate_code <> '' ORDER BY br.governorate_code
                """.formatted(LIVE)).query(String.class).list();
        return Map.of("categories", cats, "governorates", govs);
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
