package com.platform.open;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.commerce.CatalogService;
import com.platform.commerce.ShopperService;
import com.platform.commerce.StoreFulfillmentService;
import com.platform.commerce.StoreSettingsService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The marketplace catalogue: every product of every live, open, listed shop in one searchable list. It reads the same tables the shops' own
 * storefronts read (nothing is copied), so a price or stock change in a shop shows here at once.
 */
@Service
public class OpenCatalogService {
    public record Filter(String q, String category, String shop, Long minPrice, Long maxPrice, String brands, Boolean inStock, Boolean onSale, Boolean freeShipping,
                         Integer minRating, String audience, String colors, String sizes, String conditions, String governorate, String sort) {
        Filter baseOnly() { return new Filter(q, category, shop, null, null, null, null, null, null, null, null, null, null, null, null, null); }
    }

    private static final String LIVE_SHOP = "t.tenant_type = 'STORE' AND t.status IN ('TRIAL','ACTIVE','PAST_DUE')";

    private final JdbcClient jdbc;
    private final OpenUrls urls;
    private final CatalogService catalog;
    private final ShopperService shopper;
    private final StoreSettingsService settings;
    private final StoreFulfillmentService fulfilment;
    private final Cache<String, Object> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).maximumSize(64).build();

    public OpenCatalogService(JdbcClient jdbc, OpenUrls urls, CatalogService catalog, ShopperService shopper, StoreSettingsService settings, StoreFulfillmentService fulfilment) {
        this.jdbc = jdbc;
        this.urls = urls;
        this.catalog = catalog;
        this.shopper = shopper;
        this.settings = settings;
        this.fulfilment = fulfilment;
    }

    // ---- listing ---------------------------------------------------------------------------------------------------

    private static final String CTE = """
            WITH base AS (
              SELECT p.id, p.tenant_id AS shop_id, t.slug AS shop_slug, t.name AS shop_name, p.name, p.slug, p.short_description, p.brand, p.currency, p.taxonomy_id,
                p.audience, p.item_condition, p.badge, p.is_featured, p.sold_count, p.created_at,
                tx.slug AS category_slug, tx.name_ar AS category_name_ar, tx.name_en AS category_name_en, tx.path AS category_path,
                pv.min_price_minor, pv.compare_at_minor, pv.discount_pct,
                (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_url,
                (SELECT m.media_base FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_base,
                (SELECT m.media_ext FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_ext,
                rv.rating, coalesce(rv.rating_count, 0) AS rating_count,
                EXISTS (SELECT 1 FROM commerce.product_variants v JOIN commerce.inventory_items i ON i.variant_id = v.id
                        JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                        WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND i.quantity_on_hand - i.quantity_reserved > 0) AS in_stock,
                EXISTS (SELECT 1 FROM commerce.shipping_methods sm WHERE sm.tenant_id = p.tenant_id AND sm.is_active
                          AND ((sm.type = 'FIXED' AND sm.fee_minor = 0) OR (sm.type = 'FREE_ABOVE' AND sm.free_above_minor <= pv.min_price_minor)))
                  OR EXISTS (SELECT 1 FROM commerce.shipping_zones z WHERE z.tenant_id = p.tenant_id AND z.is_active AND z.free_above_minor IS NOT NULL AND z.free_above_minor <= pv.min_price_minor) AS free_shipping,
                CASE WHEN CAST(:q1 AS varchar) IS NULL THEN 0
                     WHEN commerce.norm_ar(p.name) LIKE CAST(:q1 AS varchar) || '%' THEN 2
                     WHEN commerce.norm_ar(p.name) LIKE '%' || CAST(:q1 AS varchar) || '%' THEN 1 ELSE 0 END AS rel
              FROM commerce.products p
              JOIN core.tenants t ON t.id = p.tenant_id AND @LIVE@
              JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.is_open AND sp.open_listed
              LEFT JOIN commerce.taxonomy tx ON tx.id = p.taxonomy_id
              JOIN LATERAL (
                SELECT min(v.price_minor) AS min_price_minor,
                       max(v.compare_at_price_minor) FILTER (WHERE v.compare_at_price_minor > v.price_minor) AS compare_at_minor,
                       max((v.compare_at_price_minor - v.price_minor) * 100 / v.compare_at_price_minor) FILTER (WHERE v.compare_at_price_minor > v.price_minor) AS discount_pct
                FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') pv ON pv.min_price_minor IS NOT NULL
              LEFT JOIN LATERAL (
                SELECT round(avg(r.rating)::numeric, 1) AS rating, count(*) AS rating_count FROM commerce.reviews r WHERE r.product_id = p.id AND r.status = 'APPROVED') rv ON true
              WHERE p.status = 'ACTIVE'
                AND (CAST(:shop AS varchar) IS NULL OR t.slug = CAST(:shop AS varchar))
                AND (CAST(:c AS varchar) IS NULL OR tx.path LIKE (SELECT path FROM commerce.taxonomy WHERE slug = CAST(:c AS varchar) AND is_active) || '%')
                AND (CAST(:q1 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q1 AS varchar) || '%')
                AND (CAST(:q2 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q2 AS varchar) || '%')
                AND (CAST(:q3 AS varchar) IS NULL OR p.search_text ILIKE '%' || CAST(:q3 AS varchar) || '%')
            )
            """.replace("@LIVE@", LIVE_SHOP);

    private static final String FILTER_WHERE = """
            (CAST(:minp AS bigint) IS NULL OR min_price_minor >= CAST(:minp AS bigint))
            AND (CAST(:maxp AS bigint) IS NULL OR min_price_minor <= CAST(:maxp AS bigint))
            AND (CAST(:brands AS varchar) IS NULL OR lower(brand) = ANY(string_to_array(lower(CAST(:brands AS varchar)), ',')))
            AND (NOT :instock OR in_stock) AND (NOT :onsale OR discount_pct IS NOT NULL) AND (NOT :freeship OR free_shipping)
            AND (CAST(:minr AS int) IS NULL OR coalesce(rating, 0) >= CAST(:minr AS int))
            AND (CAST(:aud AS varchar) IS NULL OR audience = 'ALL' OR audience = ANY(string_to_array(CAST(:aud AS varchar), ',')))
            AND (CAST(:cond AS varchar) IS NULL OR item_condition = ANY(string_to_array(CAST(:cond AS varchar), ',')))
            AND (CAST(:colors AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.product_options o JOIN commerce.product_option_values v ON v.option_id = o.id
                   WHERE o.product_id = base.id AND o.attribute_code = 'COLOR' AND v.value_code = ANY(string_to_array(CAST(:colors AS varchar), ','))))
            AND (CAST(:sizes AS varchar) IS NULL OR EXISTS (SELECT 1 FROM commerce.product_options o JOIN commerce.product_option_values v ON v.option_id = o.id
                   WHERE o.product_id = base.id AND o.attribute_code = 'SIZE' AND v.value_code = ANY(string_to_array(CAST(:sizes AS varchar), ','))))
            AND (CAST(:gov AS varchar) IS NULL
                 OR EXISTS (SELECT 1 FROM commerce.shipping_methods sm WHERE sm.tenant_id = base.shop_id AND sm.is_active AND sm.type IN ('FIXED','FREE_ABOVE'))
                 OR EXISTS (SELECT 1 FROM commerce.shipping_zones z WHERE z.tenant_id = base.shop_id AND z.is_active AND CAST(:gov AS varchar) = ANY(z.governorate_codes)))
            """;

    private static final Map<String, String> SORTS = Map.ofEntries(
            Map.entry("recommended", "is_featured DESC, sold_count DESC, created_at DESC"),
            Map.entry("relevance", "rel DESC, sold_count DESC, created_at DESC"),
            Map.entry("newest", "created_at DESC"),
            Map.entry("popular", "sold_count DESC, created_at DESC"),
            Map.entry("price_asc", "min_price_minor ASC, created_at DESC"),
            Map.entry("price_desc", "min_price_minor DESC, created_at DESC"),
            Map.entry("discount", "discount_pct DESC NULLS LAST, created_at DESC"),
            Map.entry("rating", "rating DESC NULLS LAST, rating_count DESC, created_at DESC"));

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").toLowerCase(java.util.Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه').trim();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec st, Filter f) {
        List<String> tokens = new ArrayList<>();
        for (String w : norm(f.q()).split("\\s+")) if (!w.isBlank() && tokens.size() < 3) tokens.add(w);
        return st.param("c", blank(f.category())).param("shop", blank(f.shop()))
                .param("q1", tokens.size() > 0 ? tokens.get(0) : null).param("q2", tokens.size() > 1 ? tokens.get(1) : null).param("q3", tokens.size() > 2 ? tokens.get(2) : null)
                .param("minp", f.minPrice()).param("maxp", f.maxPrice()).param("brands", blank(f.brands()))
                .param("instock", Boolean.TRUE.equals(f.inStock())).param("onsale", Boolean.TRUE.equals(f.onSale())).param("freeship", Boolean.TRUE.equals(f.freeShipping()))
                .param("minr", f.minRating()).param("aud", blank(f.audience())).param("cond", blank(f.conditions()))
                .param("colors", blank(f.colors())).param("sizes", blank(f.sizes())).param("gov", f.governorate() == null || f.governorate().isBlank() ? null : f.governorate().trim().toUpperCase());
    }

    public Map<String, Object> list(Page page, Filter f) {
        String sortKey = f.sort() != null && SORTS.containsKey(f.sort()) ? f.sort() : (blank(f.q()) != null ? "relevance" : "recommended");
        long total = bind(jdbc.sql(CTE + "SELECT count(*) FROM base WHERE " + FILTER_WHERE), f).query(Long.class).single();
        var rows = bind(jdbc.sql(CTE + "SELECT * FROM base WHERE " + FILTER_WHERE + " ORDER BY " + SORTS.get(sortKey) + " LIMIT :lim OFFSET :off"), f)
                .param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(decorate(Rows.camel(rows)), total);
    }

    /** What can be filtered by for the current search/category/shop: counts are within that search, so every option leads to products. */
    public Map<String, Object> facets(Filter f) {
        Filter base = f.baseOnly();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brands", Rows.camel(bind(jdbc.sql(CTE + "SELECT brand, count(*) AS n FROM base WHERE brand IS NOT NULL AND brand <> '' GROUP BY brand ORDER BY n DESC, brand LIMIT 30"), base).query().listOfRows()));
        out.put("shops", Rows.camel(bind(jdbc.sql(CTE + "SELECT shop_id, shop_slug, shop_name, count(*) AS n FROM base GROUP BY shop_id, shop_slug, shop_name ORDER BY n DESC, shop_name LIMIT 20"), base).query().listOfRows()));
        out.put("audiences", Rows.camel(bind(jdbc.sql(CTE + "SELECT audience, count(*) AS n FROM base WHERE audience <> '' GROUP BY audience ORDER BY n DESC"), base).query().listOfRows()));
        out.put("conditions", Rows.camel(bind(jdbc.sql(CTE + "SELECT item_condition AS condition, count(*) AS n FROM base GROUP BY item_condition ORDER BY n DESC"), base).query().listOfRows()));
        out.put("colors", Rows.camel(bind(jdbc.sql(CTE + """
                SELECT a.code, a.name_ar, a.name_en, a.hex, count(DISTINCT b.id) AS n FROM base b
                JOIN commerce.product_options o ON o.product_id = b.id AND o.attribute_code = 'COLOR'
                JOIN commerce.product_option_values v ON v.option_id = o.id AND v.value_code <> ''
                JOIN commerce.attribute_values a ON a.attr = 'COLOR' AND a.scale = '' AND a.code = v.value_code
                GROUP BY a.code, a.name_ar, a.name_en, a.hex, a.sort_order ORDER BY a.sort_order
                """), base).query().listOfRows()));
        out.put("sizes", Rows.camel(bind(jdbc.sql(CTE + """
                SELECT a.scale, a.code, a.name_ar, a.name_en, count(DISTINCT b.id) AS n FROM base b
                JOIN commerce.product_options o ON o.product_id = b.id AND o.attribute_code = 'SIZE'
                JOIN commerce.product_option_values v ON v.option_id = o.id AND v.value_code <> ''
                JOIN commerce.attribute_values a ON a.attr = 'SIZE' AND a.scale = o.size_scale AND a.code = v.value_code
                GROUP BY a.scale, a.code, a.name_ar, a.name_en, a.sort_order ORDER BY a.scale, a.sort_order
                """), base).query().listOfRows()));
        Integer selected = blank(f.category()) == null ? null : jdbc.sql("SELECT id FROM commerce.taxonomy WHERE slug = :s AND is_active").param("s", f.category()).query(Integer.class).optional().orElse(null);
        out.put("categories", Rows.camel(bind(jdbc.sql(CTE + """
                SELECT c.slug, c.name_ar, c.name_en, c.icon, count(*) AS n FROM base b
                JOIN commerce.taxonomy c ON c.is_active AND NOT c.is_hidden AND c.parent_id IS NOT DISTINCT FROM CAST(:pid AS int) AND b.category_path LIKE c.path || '%'
                GROUP BY c.id, c.slug, c.name_ar, c.name_en, c.icon, c.sort_order ORDER BY c.sort_order
                """), base).param("pid", selected).query().listOfRows()));
        out.put("trail", selected == null ? List.of() : Rows.camel(jdbc.sql("SELECT a.slug, a.name_ar, a.name_en FROM commerce.taxonomy t JOIN commerce.taxonomy a ON t.path LIKE a.path || '%' WHERE t.id = :i ORDER BY a.level")
                .param("i", selected).query().listOfRows()));
        var range = bind(jdbc.sql(CTE + "SELECT min(min_price_minor) AS lo, max(min_price_minor) AS hi, count(*) AS n FROM base"), base).query().singleRow();
        out.put("priceMinMinor", range.get("lo"));
        out.put("priceMaxMinor", range.get("hi"));
        out.put("total", range.get("n"));
        return out;
    }

    /** Search-as-you-type: a few products, matching categories and matching shops. */
    public Map<String, Object> suggest(String q) {
        if (norm(q).length() < 2) return Map.of("products", List.of(), "categories", List.of(), "shops", List.of());
        Filter f = new Filter(q, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "relevance");
        var products = decorate(Rows.camel(bind(jdbc.sql(CTE + "SELECT id, shop_id, shop_slug, shop_name, name, slug, min_price_minor, currency, image_url, image_media_base, image_media_ext FROM base WHERE " + FILTER_WHERE
                + " ORDER BY rel DESC, sold_count DESC LIMIT 6"), f).query().listOfRows()));
        var cats = Rows.camel(jdbc.sql("""
                SELECT t.name_ar, t.name_en, t.slug, t.icon FROM commerce.taxonomy t
                WHERE t.is_active AND NOT t.is_hidden AND t.search_text ILIKE '%' || :q || '%'
                  AND EXISTS (SELECT 1 FROM commerce.products p JOIN core.tenants s ON s.id = p.tenant_id AND @LIVE@ JOIN commerce.taxonomy c ON c.id = p.taxonomy_id
                              WHERE p.status = 'ACTIVE' AND c.path LIKE t.path || '%')
                ORDER BY t.level DESC, t.sort_order LIMIT 4
                """.replace("@LIVE@", LIVE_SHOP.replace("t.", "s."))).param("q", norm(q)).query().listOfRows());
        var shops = Rows.camel(jdbc.sql("""
                SELECT t.id, t.slug, t.name FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.open_listed
                WHERE @LIVE@ AND (t.name ILIKE :q OR t.slug ILIKE :q) ORDER BY t.name LIMIT 3
                """.replace("@LIVE@", LIVE_SHOP)).param("q", "%" + q.trim() + "%").query().listOfRows());
        return Map.of("products", products, "categories", cats, "shops", shops);
    }

    // ---- home --------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Map<String, Object> home() {
        return (Map<String, Object>) cache.get("home", k -> buildHome());
    }

    private Map<String, Object> buildHome() {
        Filter none = new Filter(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories(1));
        out.put("deals", section(none, "discount_pct IS NOT NULL AND in_stock", "discount_pct DESC, sold_count DESC", 12));
        out.put("trending", section(none, "in_stock", "sold_count DESC, created_at DESC", 12));
        out.put("newArrivals", section(none, "in_stock", "created_at DESC", 12));
        out.put("topRated", section(none, "in_stock AND rating >= 4 AND rating_count >= 1", "rating DESC, rating_count DESC", 12));
        out.put("featured", section(none, "in_stock AND is_featured", "sold_count DESC, created_at DESC", 8));
        out.put("shops", shops(null, Page.of(1, 12), "popular").get("data"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var c : categories(1).stream().sorted((a, b) -> Long.compare(((Number) b.get("n")).longValue(), ((Number) a.get("n")).longValue())).limit(4).toList()) {
            Filter cf = new Filter(null, (String) c.get("slug"), null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            Map<String, Object> row = new LinkedHashMap<>(c);
            row.put("products", section(cf, "in_stock", "sold_count DESC, created_at DESC", 8));
            rows.add(row);
        }
        out.put("categoryRows", rows);
        var stat = bind(jdbc.sql(CTE + "SELECT count(*) AS products, count(DISTINCT shop_id) AS shops FROM base"), none).query().singleRow();
        out.put("stats", Map.of("products", stat.get("products"), "shops", stat.get("shops")));
        return out;
    }

    private List<Map<String, Object>> section(Filter f, String extraWhere, String order, int limit) {
        return decorate(Rows.camel(bind(jdbc.sql(CTE + "SELECT * FROM base WHERE " + FILTER_WHERE + " AND " + extraWhere + " ORDER BY " + order + " LIMIT " + limit), f).query().listOfRows()));
    }

    /** Top-level (level 1) or first two levels of the standard categories, with how many marketplace products each holds. Empty categories are left out. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> categories(int maxLevel) {
        return (List<Map<String, Object>>) cache.get("cats" + maxLevel, k -> Rows.camel(jdbc.sql("""
                SELECT n.id, n.parent_id, n.level, n.slug, n.name_ar, n.name_en, n.icon, count(p.id) AS n
                FROM commerce.taxonomy n
                JOIN commerce.taxonomy tx ON tx.path LIKE n.path || '%'
                JOIN commerce.products p ON p.taxonomy_id = tx.id AND p.status = 'ACTIVE'
                JOIN core.tenants t ON t.id = p.tenant_id AND @LIVE@
                JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.is_open AND sp.open_listed
                WHERE n.is_active AND NOT n.is_hidden AND n.level <= :lvl
                  AND EXISTS (SELECT 1 FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE')
                GROUP BY n.id, n.parent_id, n.level, n.slug, n.name_ar, n.name_en, n.icon, n.sort_order ORDER BY n.level, n.sort_order
                """.replace("@LIVE@", LIVE_SHOP)).param("lvl", maxLevel).query().listOfRows()));
    }

    /** Level 1 categories each with their level 2 children, for the mega menu. */
    public List<Map<String, Object>> categoryTree() {
        List<Map<String, Object>> flat = categories(2);
        Map<Object, Map<String, Object>> roots = new LinkedHashMap<>();
        for (var c : flat) if (((Number) c.get("level")).intValue() == 1) { Map<String, Object> m = new LinkedHashMap<>(c); m.put("children", new ArrayList<Map<String, Object>>()); roots.put(c.get("id"), m); }
        for (var c : flat) if (((Number) c.get("level")).intValue() == 2 && roots.containsKey(c.get("parentId"))) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> kids = (List<Map<String, Object>>) roots.get(c.get("parentId")).get("children");
            kids.add(c);
        }
        return new ArrayList<>(roots.values());
    }

    // ---- shops -------------------------------------------------------------------------------------------------------

    public Map<String, Object> shops(String q, Page page, String sort) {
        String like = blank(q) == null ? null : "%" + q.trim() + "%";
        String order = "rating".equals(sort) ? "rating DESC NULLS LAST, product_count DESC" : "name".equals(sort) ? "t.name" : "product_count DESC, sold DESC, t.name";
        String where = LIVE_SHOP + " AND (CAST(:like AS varchar) IS NULL OR t.name ILIKE CAST(:like AS varchar) OR t.slug ILIKE CAST(:like AS varchar))";
        long total = jdbc.sql("SELECT count(*) FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.open_listed WHERE " + where
                + " AND EXISTS (SELECT 1 FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE')").param("like", like).query(Long.class).single();
        var rows = Rows.camel(jdbc.sql(SHOP_SELECT + " WHERE " + where + " AND EXISTS (SELECT 1 FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') ORDER BY " + order + " LIMIT :lim OFFSET :off")
                .param("like", like).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows());
        return page.wrap(decorateShops(rows), total);
    }

    private static final String SHOP_SELECT = """
            SELECT t.id, t.slug, t.name, sp.short_description, sp.is_open, sp.closed_message, b.primary_color, b.logo_file_id, t.created_at,
              (SELECT count(*) FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS product_count,
              (SELECT coalesce(sum(p.sold_count), 0) FROM commerce.products p WHERE p.tenant_id = t.id AND p.status = 'ACTIVE') AS sold,
              (SELECT round(avg(r.rating)::numeric, 1) FROM commerce.reviews r WHERE r.tenant_id = t.id AND r.status = 'APPROVED') AS rating,
              (SELECT count(*) FROM commerce.reviews r WHERE r.tenant_id = t.id AND r.status = 'APPROVED') AS rating_count
            FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.open_listed LEFT JOIN core.branding b ON b.tenant_id = t.id
            """;

    public Map<String, Object> shop(String slug) {
        var rows = Rows.camel(jdbc.sql(SHOP_SELECT + " WHERE t.slug = :s AND " + LIVE_SHOP).param("s", slug).query().listOfRows());
        if (rows.isEmpty()) throw BusinessException.notFound("SHOP_NOT_FOUND", "Shop not found");
        return decorateShops(rows).get(0);
    }

    private List<Map<String, Object>> decorateShops(List<Map<String, Object>> rows) {
        Map<UUID, String> hosts = urls.hosts(rows.stream().map(r -> (UUID) r.get("id")).toList());
        for (var r : rows) {
            String host = hosts.get((UUID) r.get("id"));
            r.put("host", host);
            r.put("url", urls.baseUrl(host));
            r.put("logoUrl", urls.logo(host, r.remove("logoFileId")));
            r.remove("sold");
        }
        return rows;
    }

    // ---- product detail ----------------------------------------------------------------------------------------------

    public Map<String, Object> product(String shopSlug, String productSlug) {
        var shopRow = jdbc.sql("SELECT t.id FROM core.tenants t JOIN commerce.store_profiles sp ON sp.tenant_id = t.id AND sp.open_listed WHERE t.slug = :s AND " + LIVE_SHOP)
                .param("s", shopSlug).query(UUID.class).optional().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        Map<String, Object> p = catalog.detailBySlug(shopRow, productSlug);
        UUID productId = (UUID) p.get("id");
        Map<String, Object> shop = shop(shopSlug);
        String host = (String) shop.get("host");
        p.put("shop", shop);
        p.put("shopId", shopRow);
        p.put("reviews", shopper.publicReviews(shopRow, productId));
        List<Map<String, Object>> images = new ArrayList<>();
        @SuppressWarnings("unchecked") List<Map<String, Object>> media = (List<Map<String, Object>>) p.get("media");
        for (var m : media) {
            String base = (String) m.get("mediaBase"), ext = (String) m.get("mediaExt"), url = (String) m.get("url");
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", m.get("id"));
            im.put("alt", m.get("altText"));
            im.put("optionValue", m.get("optionValue"));
            im.put("thumb", urls.image(host, base, ext, url, "thumb"));
            im.put("medium", urls.image(host, base, ext, url, "medium"));
            im.put("original", urls.image(host, base, ext, url, "original"));
            images.add(im);
        }
        p.put("images", images);
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("shippingMethods", settings.shippingMethods(shopRow, true));
        delivery.put("zones", fulfilment.zones(shopRow, true));
        delivery.put("paymentMethods", settings.paymentMethods(shopRow).stream().filter(m -> Boolean.TRUE.equals(m.get("enabled"))).toList());
        p.put("delivery", delivery);
        List<Map<String, Object>> more = catalog.related(shopRow, productId, 8);
        for (var r : more) { r.put("shopId", shopRow); r.put("shopSlug", shopSlug); r.put("shopName", shop.get("name")); }
        p.put("moreFromShop", decorate(more));
        Object taxonomyId = p.get("taxonomyId");
        p.put("similar", taxonomyId == null ? List.of() : decorate(Rows.camel(jdbc.sql(CTE.replace("WHERE p.status = 'ACTIVE'", "WHERE p.status = 'ACTIVE' AND p.id <> :self AND p.tenant_id <> :selfShop AND p.taxonomy_id = :tax")
                + "SELECT * FROM base WHERE in_stock ORDER BY sold_count DESC, created_at DESC LIMIT 8")
                .param("self", productId).param("selfShop", shopRow).param("tax", taxonomyId)
                .param("c", null).param("shop", null).param("q1", null).param("q2", null).param("q3", null).query().listOfRows())));
        return p;
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    /** Adds absolute picture links and the shop card to product rows (the raw media columns are removed). */
    List<Map<String, Object>> decorate(Collection<Map<String, Object>> rows) {
        Map<UUID, String> hosts = urls.hosts(new LinkedHashSet<>(rows.stream().map(r -> (UUID) r.get("shopId")).toList()));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (var r : rows) {
            String host = hosts.get((UUID) r.get("shopId"));
            Object base = r.remove("imageMediaBase"), ext = r.remove("imageMediaExt"), url = r.remove("imageUrl");
            r.put("image", host == null ? null : urls.images(host, (String) base, (String) ext, (String) url));
            Map<String, Object> shop = new LinkedHashMap<>();
            shop.put("id", r.get("shopId"));
            shop.put("slug", r.remove("shopSlug"));
            shop.put("name", r.remove("shopName"));
            shop.put("host", host);
            r.put("shop", shop);
            r.remove("rel");
            r.remove("categoryPath");
            out.add(r);
        }
        return out;
    }
}
