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
    public record Filter(String q, String category, Long minPrice, Long maxPrice, String brands, Boolean inStock, Boolean onSale, Integer minRating, String sort) {}

    private final JdbcClient jdbc;

    public StoreSearchService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Same folding as commerce.norm_ar(): no diacritics/tatweel, alef forms to alef, ya/alef-maqsura, ta-marbuta/ha, lower case. */
    static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").toLowerCase(java.util.Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه').trim();
    }

    private static final String CTE = """
            WITH RECURSIVE cat AS (
              SELECT id FROM commerce.categories WHERE tenant_id = :t AND slug = CAST(:c AS varchar) AND is_active
              UNION ALL SELECT ch.id FROM commerce.categories ch JOIN cat ON ch.parent_id = cat.id WHERE ch.is_active
            ), base AS (
              SELECT p.id, p.name, p.slug, p.short_description, p.brand, p.currency, p.category_id, p.badge, p.is_featured, p.sold_count, p.created_at, p.sort_order,
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
                AND (CAST(:c AS varchar) IS NULL OR p.category_id IN (SELECT id FROM cat))
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
        List<String> tokens = new ArrayList<>();
        for (String w : norm(f.q()).split("\\s+")) if (!w.isBlank() && tokens.size() < 3) tokens.add(w);
        String c = f.category() == null || f.category().isBlank() ? null : f.category();
        return st.param("t", t).param("c", c)
                .param("q1", tokens.size() > 0 ? tokens.get(0) : null).param("q2", tokens.size() > 1 ? tokens.get(1) : null).param("q3", tokens.size() > 2 ? tokens.get(2) : null);
    }

    private static final String FILTER_WHERE = """
            (CAST(:minp AS bigint) IS NULL OR min_price_minor >= CAST(:minp AS bigint))
            AND (CAST(:maxp AS bigint) IS NULL OR min_price_minor <= CAST(:maxp AS bigint))
            AND (CAST(:brands AS varchar) IS NULL OR lower(brand) = ANY(string_to_array(lower(CAST(:brands AS varchar)), ',')))
            AND (NOT :instock OR in_stock) AND (NOT :onsale OR discount_pct IS NOT NULL)
            AND (CAST(:minr AS int) IS NULL OR coalesce(rating, 0) >= CAST(:minr AS int))
            """;

    private JdbcClient.StatementSpec bindFilters(JdbcClient.StatementSpec st, Filter f) {
        return st.param("minp", f.minPrice()).param("maxp", f.maxPrice()).param("brands", f.brands() == null || f.brands().isBlank() ? null : f.brands())
                .param("instock", Boolean.TRUE.equals(f.inStock())).param("onsale", Boolean.TRUE.equals(f.onSale())).param("minr", f.minRating());
    }

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
        var cats = Rows.camel(jdbc.sql("SELECT name, slug FROM commerce.categories WHERE tenant_id = :t AND is_active AND commerce.norm_ar(name) ILIKE '%' || :q || '%' ORDER BY name LIMIT 4")
                .param("t", tenantId).param("q", norm(q)).query().listOfRows());
        return Map.of("products", products, "categories", cats);
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
