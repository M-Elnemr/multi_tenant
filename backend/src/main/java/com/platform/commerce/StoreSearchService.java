package com.platform.commerce;

import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Storefront product search: Arabic-aware text match, category subtree, price/brand/stock/sale/rating filters, sorting and facets. */
@Service
public class StoreSearchService {
    public record Filter(String q, String category, Long minPrice, Long maxPrice, String brands, Boolean inStock, Boolean onSale, Integer minRating, String sort,
                         String audience, String colors, String sizes, String conditions) {
        public Filter(String q, String category, Long minPrice, Long maxPrice, String brands, Boolean inStock, Boolean onSale, Integer minRating, String sort) {
            this(q, category, minPrice, maxPrice, brands, inStock, onSale, minRating, sort, null, null, null, null);
        }
    }

    private final JdbcClient jdbc;

    public StoreSearchService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same folding as commerce.norm_ar(): no diacritics/tatweel, alef forms to alef, ya/alef-maqsura, ta-marbuta/ha, lower case. */
    static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").toLowerCase(java.util.Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه').trim();
    }

    private static final String CTE = """
            WITH cat AS (
              SELECT x.id FROM commerce.taxonomy x WHERE x.is_active AND x.path LIKE (SELECT path FROM commerce.taxonomy WHERE slug = CAST(:c AS varchar) AND is_active) || '%'
            ), base AS (
              SELECT p.id, p.name, p.slug, p.short_description, p.brand, p.currency, p.taxonomy_id, p.audience, p.item_condition, p.badge, p.is_featured, p.sold_count, p.created_at, p.sort_order,
                (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_price_minor,
                (SELECT max(v.compare_at_price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.compare_at_price_minor > v.price_minor) AS compare_at_minor,
                (SELECT max((v.compare_at_price_minor - v.price_minor) * 100 / v.compare_at_price_minor) FROM commerce.product_variants v
                   WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.compare_at_price_minor > v.price_minor) AS discount_pct,
                (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_url,
                (SELECT m.media_base FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_base,
                (SELECT m.media_ext FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_ext,
                (SELECT round(avg(r.rating)::numeric, 1) FROM commerce.reviews r WHERE r.product_id = p.id AND r.status = 'APPROVED') AS rating,
                (SELECT count(*) FROM commerce.reviews r WHERE r.product_id = p.id AND r.status = 'APPROVED') AS rating_count,
                EXISTS (SELECT 1 FROM commerce.product_variants v JOIN commerce.inventory_items i ON i.variant_id = v.id
                        JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                        WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND i.quantity_on_hand - i.quantity_reserved > 0) AS in_stock
              FROM commerce.products p
              WHERE p.tenant_id = :t AND p.status = 'ACTIVE'
                AND (CAST(:c AS varchar) IS NULL OR p.taxonomy_id IN (SELECT id FROM cat))
                AND (CAST(:q1 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q1 AS varchar) || '%')
                AND (CAST(:q2 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q2 AS varchar) || '%')
                AND (CAST(:q3 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q3 AS varchar) || '%')
            )
            """;

    private static final Map<String, String> SORTS = Map.of(
            "newest", "created_at DESC",
            "price_asc", "min_price_minor ASC NULLS LAST, created_at DESC",
            "price_desc", "min_price_minor DESC NULLS LAST, created_at DESC",
            "popular", "sold_count DESC, created_at DESC",
            "discount", "discount_pct DESC NULLS LAST, created_at DESC",
            "name", "name ASC");

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec st, UUID t, Filter f) {
        List<String> tokens = tokens(t, f.q());
        String c = f.category() == null || f.category().isBlank() ? null : f.category();
        return st.param("t", t).param("c", c)
                .param("q1", tokens.size() > 0 ? tokens.get(0) : null).param("q2", tokens.size() > 1 ? tokens.get(1) : null).param("q3", tokens.size() > 2 ? tokens.get(2) : null);
    }

    /**
     * The words every result must contain (at most 3): the Arabic article is dropped («الجينز» finds «جينز سليم»), and when the query has several words, a word no product of
     * the shop contains is left out instead of emptying the results («جينز رجالي وسطي» finds the men's jeans).
     */
    public List<String> tokens(UUID t, String q) {
        List<String> words = new ArrayList<>();
        for (String w : norm(q).split("\\s+")) {
            if (w.isBlank()) continue;
            words.add(w.length() >= 5 && (w.startsWith("ال") || w.startsWith("لل")) ? w.substring(2) : w.length() >= 6 && (w.startsWith("وال") || w.startsWith("بال")) ? w.substring(3) : w);
            if (words.size() >= 6) break;
        }
        List<String> kept = words;
        if (words.size() > 1) {
            kept = new ArrayList<>();
            for (String w : words)
                if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM commerce.products WHERE tenant_id = :t AND status = 'ACTIVE' AND search_text ILIKE '%' || :w || '%')").param("t", t).param("w", w).query(Boolean.class).single()) kept.add(w);
            if (kept.isEmpty()) kept = words;
        }
        return kept.size() > 3 ? kept.subList(0, 3) : kept;
    }

    private static final String FILTER_WHERE = """
            (CAST(:minp AS bigint) IS NULL OR min_price_minor >= CAST(:minp AS bigint))
            AND (CAST(:maxp AS bigint) IS NULL OR min_price_minor <= CAST(:maxp AS bigint))
            AND (CAST(:brands AS varchar) IS NULL OR lower(brand) = ANY(string_to_array(lower(CAST(:brands AS varchar)), ',')))
            AND (NOT :instock OR in_stock) AND (NOT :onsale OR discount_pct IS NOT NULL)
            AND (CAST(:minr AS int) IS NULL OR coalesce(rating, 0) >= CAST(:minr AS int))
            AND (CAST(:aud AS varchar) IS NULL OR audience = 'ALL' OR audience = ANY(string_to_array(CAST(:aud AS varchar), ',')))
            AND (CAST(:cond AS varchar) IS NULL OR item_condition = ANY(string_to_array(CAST(:cond AS varchar), ',')))
            AND (CAST(:colors AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.product_options o JOIN commerce.product_option_values v ON v.option_id = o.id
                   WHERE o.product_id = base.id AND o.attribute_code = 'COLOR' AND v.value_code = ANY(string_to_array(CAST(:colors AS varchar), ','))))
            AND (CAST(:sizes AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.product_options o JOIN commerce.product_option_values v ON v.option_id = o.id
                   WHERE o.product_id = base.id AND o.attribute_code = 'SIZE' AND v.value_code = ANY(string_to_array(CAST(:sizes AS varchar), ','))))
            """;

    private JdbcClient.StatementSpec bindFilters(JdbcClient.StatementSpec st, Filter f) {
        return st.param("minp", f.minPrice()).param("maxp", f.maxPrice()).param("brands", f.brands() == null || f.brands().isBlank() ? null : f.brands())
                .param("instock", Boolean.TRUE.equals(f.inStock())).param("onsale", Boolean.TRUE.equals(f.onSale())).param("minr", f.minRating())
                .param("aud", csv(f.audience())).param("cond", csv(f.conditions())).param("colors", csv(f.colors())).param("sizes", csv(f.sizes()));
    }

    /** Comma list from the URL, upper/lower-cased as needed by the caller; blank means "no filter". */
    private static String csv(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    public Map<String, Object> list(UUID tenantId, Page page, Filter f) {
        String order = SORTS.getOrDefault(f.sort() == null ? "newest" : f.sort(), SORTS.get("newest"));
        long total = bindFilters(bind(jdbc.sql(CTE + "SELECT count(*) FROM base WHERE " + FILTER_WHERE), tenantId, f), f).query(Long.class).single();
        var rows = bindFilters(bind(jdbc.sql(CTE + "SELECT * FROM base WHERE " + FILTER_WHERE + " ORDER BY " + order + " LIMIT :lim OFFSET :off"), tenantId, f), f)
                .param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    /** Brands with counts and the price range for the current search/category (ignores the brand/price filters themselves so options stay visible). */
    public Map<String, Object> facets(UUID tenantId, Filter f) {
        Filter base = new Filter(f.q(), f.category(), null, null, null, null, null, null, null);
        var brands = Rows.camel(bindFilters(bind(jdbc.sql(CTE + "SELECT brand, count(*) AS n FROM base WHERE " + FILTER_WHERE + " AND brand IS NOT NULL AND brand <> '' GROUP BY brand ORDER BY n DESC, brand LIMIT 30"), tenantId, base), base).query().listOfRows());
        var range = bindFilters(bind(jdbc.sql(CTE + "SELECT min(min_price_minor) AS lo, max(min_price_minor) AS hi, count(*) AS n FROM base WHERE " + FILTER_WHERE), tenantId, base), base).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brands", brands);
        // standard filters: counts within the current search/category, so only options that lead to products are offered
        out.put("audiences", Rows.camel(bind(jdbc.sql(CTE + "SELECT audience, count(*) AS n FROM base WHERE audience <> '' GROUP BY audience ORDER BY n DESC"), tenantId, base).query().listOfRows()));
        out.put("colors", Rows.camel(bind(jdbc.sql(CTE + """
                SELECT a.code, a.name_ar, a.name_en, a.hex, count(DISTINCT b.id) AS n FROM base b
                JOIN commerce.product_options o ON o.product_id = b.id AND o.attribute_code = 'COLOR'
                JOIN commerce.product_option_values v ON v.option_id = o.id AND v.value_code <> ''
                JOIN commerce.attribute_values a ON a.attr = 'COLOR' AND a.scale = '' AND a.code = v.value_code
                GROUP BY a.code, a.name_ar, a.name_en, a.hex, a.sort_order ORDER BY a.sort_order
                """), tenantId, base).query().listOfRows()));
        out.put("sizes", Rows.camel(bind(jdbc.sql(CTE + """
                SELECT a.scale, a.code, a.name_ar, a.name_en, count(DISTINCT b.id) AS n FROM base b
                JOIN commerce.product_options o ON o.product_id = b.id AND o.attribute_code = 'SIZE'
                JOIN commerce.product_option_values v ON v.option_id = o.id AND v.value_code <> ''
                JOIN commerce.attribute_values a ON a.attr = 'SIZE' AND a.scale = o.size_scale AND a.code = v.value_code
                GROUP BY a.scale, a.code, a.name_ar, a.name_en, a.sort_order ORDER BY a.scale, a.sort_order
                """), tenantId, base).query().listOfRows()));
        out.put("conditions", Rows.camel(bind(jdbc.sql(CTE + "SELECT item_condition AS condition, count(*) AS n FROM base GROUP BY item_condition ORDER BY n DESC"), tenantId, base).query().listOfRows()));
        out.put("priceMinMinor", range.get("lo"));
        out.put("priceMaxMinor", range.get("hi"));
        out.put("total", range.get("n"));
        return out;
    }

    /** Search-as-you-type: a few products and matching categories. */
    public Map<String, Object> suggest(UUID tenantId, String q) {
        Filter f = new Filter(q, null, null, null, null, null, null, null, "popular");
        if (norm(q).length() < 2) return Map.of("products", List.of(), "categories", List.of());
        var products = Rows.camel(bindFilters(bind(jdbc.sql(CTE + "SELECT id, name, slug, min_price_minor, currency, image_url, image_media_base, image_media_ext FROM base WHERE " + FILTER_WHERE + " ORDER BY sold_count DESC, created_at DESC LIMIT 6"), tenantId, f), f).query().listOfRows());
        var cats = Rows.camel(jdbc.sql("""
                SELECT t.name_ar AS name, t.name_en, t.slug FROM commerce.taxonomy t
                WHERE t.is_active AND t.search_text ILIKE '%' || :q || '%'
                  AND EXISTS (SELECT 1 FROM commerce.products p JOIN commerce.taxonomy c ON c.id = p.taxonomy_id WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND c.path LIKE t.path || '%')
                ORDER BY t.level DESC, t.sort_order LIMIT 4
                """).param("t", tenantId).param("q", norm(q)).query().listOfRows());
        return Map.of("products", products, "categories", cats);
    }

    /** Categories (leaf, with their parent) that hold active products of this shop, most stocked first, with counts and the price range: what the AI seller is told the shop sells. */
    public List<Map<String, Object>> categoryCounts(UUID tenantId, int limit) {
        return Rows.camel(jdbc.sql("""
                SELECT c.name_ar, c.name_en, c.slug, par.name_ar AS parent_ar, par.name_en AS parent_en, count(*) AS n,
                       string_agg(DISTINCT nullif(p.audience, ''), ',') AS audiences,
                       min((SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE')) AS min_minor,
                       max((SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE')) AS max_minor
                FROM commerce.products p JOIN commerce.taxonomy c ON c.id = p.taxonomy_id LEFT JOIN commerce.taxonomy par ON par.id = c.parent_id
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE'
                GROUP BY c.id, c.name_ar, c.name_en, c.slug, par.name_ar, par.name_en ORDER BY n DESC, c.name_en LIMIT :lim
                """).param("t", tenantId).param("lim", limit).query().listOfRows());
    }

    /** Names and starting prices of the shop's best sellers (in stock first), for the AI's catalog digest. */
    public List<Map<String, Object>> topProducts(UUID tenantId, int limit) {
        return Rows.camel(jdbc.sql("""
                SELECT p.name, p.slug, (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_minor, p.currency
                FROM commerce.products p
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE'
                ORDER BY EXISTS (SELECT 1 FROM commerce.product_variants v JOIN commerce.inventory_items i ON i.variant_id = v.id
                                 WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND i.quantity_on_hand - i.quantity_reserved > 0) DESC,
                         p.sold_count DESC, p.created_at DESC LIMIT :lim
                """).param("t", tenantId).param("lim", limit).query().listOfRows());
    }

    /** The cards' data (name, price, picture) of these products, in the same shape as {@link #list} rows, for the pictures the AI seller sends on purpose. Unknown or inactive slugs are skipped. */
    public List<Map<String, Object>> bySlugs(UUID tenantId, List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) return List.of();
        return Rows.camel(jdbc.sql("""
                SELECT p.name, p.slug, p.currency,
                  (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_price_minor,
                  (SELECT max(v.compare_at_price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.compare_at_price_minor > v.price_minor) AS compare_at_minor,
                  (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_url,
                  (SELECT m.media_base FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_base,
                  (SELECT m.media_ext FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_ext
                FROM commerce.products p
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND p.slug = ANY(string_to_array(CAST(:s AS varchar), ','))
                """).param("t", tenantId).param("s", String.join(",", slugs)).query().listOfRows());
    }

    /** Slugs of the categories (with active products in this shop) whose Arabic or English name contains one of the words. */
    public List<String> categoriesMatching(UUID tenantId, List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            if (w.length() < 2) continue;
            for (String slug : jdbc.sql("""
                    SELECT t.slug FROM commerce.taxonomy t
                    WHERE t.is_active AND t.search_text ILIKE '%' || :q || '%'
                      AND EXISTS (SELECT 1 FROM commerce.products p JOIN commerce.taxonomy c ON c.id = p.taxonomy_id WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND c.path LIKE t.path || '%')
                    ORDER BY t.level, t.sort_order LIMIT 2
                    """).param("t", tenantId).param("q", norm(w)).query(String.class).list()) if (!out.contains(slug)) out.add(slug);
        }
        return out;
    }

    /** Home page sections in one call. */
    public Map<String, Object> home(UUID tenantId) {
        Filter none = new Filter(null, null, null, null, null, null, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("featured", section(tenantId, none, "is_featured DESC, sort_order, created_at DESC", "is_featured"));
        out.put("newest", section(tenantId, none, "created_at DESC", null));
        out.put("offers", section(tenantId, new Filter(null, null, null, null, null, null, true, null, null), "discount_pct DESC NULLS LAST", null));
        out.put("bestSellers", section(tenantId, none, "sold_count DESC, created_at DESC", "sold_count > 0"));
        out.put("banners", Rows.camel(jdbc.sql("SELECT id, image_file_id, title, subtitle, link_url FROM commerce.banners WHERE tenant_id = :t AND is_active ORDER BY sort_order, created_at LIMIT 8").param("t", tenantId).query().listOfRows()));
        return out;
    }

    private List<Map<String, Object>> section(UUID tenantId, Filter f, String order, String extraWhere) {
        return Rows.camel(bindFilters(bind(jdbc.sql(CTE + "SELECT * FROM base WHERE " + FILTER_WHERE + (extraWhere == null ? "" : " AND " + extraWhere) + " ORDER BY " + order + " LIMIT 8"), tenantId, f), f).query().listOfRows());
    }
}
