package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    public record OptionReq(String name, List<String> values) {}
    public record StockReq(UUID branchId, int quantity) {}
    public record VariantReq(String sku, long priceMinor, Long compareAtPriceMinor, String barcode, Integer weightGrams,
                             Map<String, String> optionValues, List<StockReq> stock) {}
    public record MediaReq(String url, String altText) {}
    public record ProductReq(String name, String description, String shortDescription, UUID categoryId, String brand,
                             String status, List<OptionReq> options, List<VariantReq> variants, List<MediaReq> media) {}

    private final JdbcClient jdbc;
    private final EntitlementService ent;
    private final AuditService audit;

    public CatalogService(JdbcClient jdbc, EntitlementService ent, AuditService audit) {
        this.jdbc = jdbc;
        this.ent = ent;
        this.audit = audit;
    }

    // ---- categories ---------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createCategory(UUID tenantId, String name, UUID parentId, String description, int sortOrder) {
        if (name == null || name.isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Name is required");
        if (parentId != null) requireOwned("commerce.categories", parentId, tenantId);
        String slug = uniqueSlug("commerce.categories", tenantId, slugify(name));
        UUID id = jdbc.sql("""
                INSERT INTO commerce.categories (tenant_id, parent_id, name, slug, description, sort_order)
                VALUES (:t, :p, :n, :s, :d, :o) RETURNING id
                """).param("t", tenantId).param("p", parentId).param("n", name.trim()).param("s", slug).param("d", description).param("o", sortOrder)
                .query(UUID.class).single();
        return Rows.camel(jdbc.sql("SELECT id, parent_id, name, slug, description, sort_order, is_active FROM commerce.categories WHERE id = :i AND tenant_id = :t")
                .param("i", id).param("t", tenantId).query().singleRow());
    }

    public List<Map<String, Object>> categories(UUID tenantId, boolean onlyActive) {
        return Rows.camel(jdbc.sql("""
                SELECT id, parent_id, name, slug, description, sort_order, is_active FROM commerce.categories
                WHERE tenant_id = :t AND (NOT :a OR is_active) ORDER BY sort_order, name
                """).param("t", tenantId).param("a", onlyActive).query().listOfRows());
    }

    @Transactional
    public void updateCategory(UUID tenantId, UUID id, String name, String description, Boolean active, Integer sortOrder) {
        requireOwned("commerce.categories", id, tenantId);
        jdbc.sql("""
                UPDATE commerce.categories SET name = coalesce(:n, name), description = coalesce(:d, description),
                       is_active = coalesce(:a, is_active), sort_order = coalesce(:o, sort_order), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("n", name).param("d", description).param("a", active).param("o", sortOrder).param("i", id).param("t", tenantId).update();
    }

    // ---- products -----------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createProduct(UUID tenantId, UUID actor, ProductReq r) {
        if (r.name() == null || r.name().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Product name is required");
        if (r.variants() == null || r.variants().isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "At least one variant is required");
        long existing = jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND status <> 'ARCHIVED'").param("t", tenantId).query(Long.class).single();
        ent.requireCapacity(tenantId, "max_products", existing);
        if (r.categoryId() != null) requireOwned("commerce.categories", r.categoryId(), tenantId);
        String status = r.status() == null ? "ACTIVE" : r.status();
        if (!Set.of("DRAFT", "ACTIVE").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");

        List<OptionReq> options = r.options() == null ? List.of() : r.options();
        validateVariantsAgainstOptions(options, r.variants());

        UUID productId = jdbc.sql("""
                INSERT INTO commerce.products (tenant_id, category_id, name, slug, description, short_description, brand, status, has_variants)
                VALUES (:t, :c, :n, :s, :d, :sd, :b, :st, :hv) RETURNING id
                """).param("t", tenantId).param("c", r.categoryId()).param("n", r.name().trim())
                .param("s", uniqueSlug("commerce.products", tenantId, slugify(r.name())))
                .param("d", r.description() == null ? "" : r.description()).param("sd", r.shortDescription()).param("b", r.brand())
                .param("st", status).param("hv", !options.isEmpty()).query(UUID.class).single();

        Map<String, UUID> valueIds = new LinkedHashMap<>();   // "Option=Value" -> option_value id
        int oi = 0;
        for (OptionReq o : options) {
            UUID optionId = jdbc.sql("INSERT INTO commerce.product_options (product_id, name, sort_order) VALUES (:p, :n, :o) RETURNING id")
                    .param("p", productId).param("n", o.name()).param("o", oi++).query(UUID.class).single();
            int vi = 0;
            for (String v : o.values()) {
                UUID vid = jdbc.sql("INSERT INTO commerce.product_option_values (option_id, value, sort_order) VALUES (:o, :v, :s) RETURNING id")
                        .param("o", optionId).param("v", v).param("s", vi++).query(UUID.class).single();
                valueIds.put(o.name() + "=" + v, vid);
            }
        }
        for (VariantReq v : r.variants()) {
            String combo = comboKey(options, v.optionValues());
            UUID variantId;
            try {
                variantId = jdbc.sql("""
                        INSERT INTO commerce.product_variants (tenant_id, product_id, sku, barcode, price_minor, compare_at_price_minor, weight_grams, combo_key)
                        VALUES (:t, :p, :sku, :bc, :pr, :cp, :w, :ck) RETURNING id
                        """).param("t", tenantId).param("p", productId).param("sku", v.sku().trim()).param("bc", v.barcode())
                        .param("pr", v.priceMinor()).param("cp", v.compareAtPriceMinor()).param("w", v.weightGrams()).param("ck", combo)
                        .query(UUID.class).single();
            } catch (DuplicateKeyException e) {
                throw BusinessException.conflict("SKU_TAKEN", "SKU already exists: " + v.sku());
            }
            if (v.optionValues() != null)
                for (var e : v.optionValues().entrySet())
                    jdbc.sql("INSERT INTO commerce.variant_option_values (variant_id, option_value_id) VALUES (:v, :o)")
                            .param("v", variantId).param("o", valueIds.get(e.getKey() + "=" + e.getValue())).update();
            if (v.stock() != null)
                for (StockReq s : v.stock()) setInitialStock(tenantId, actor, variantId, s);
        }
        if (r.media() != null) {
            int mi = 0;
            for (MediaReq m : r.media())
                jdbc.sql("INSERT INTO commerce.product_media (tenant_id, product_id, url, sort_order, alt_text) VALUES (:t, :p, :u, :o, :a)")
                        .param("t", tenantId).param("p", productId).param("u", m.url()).param("o", mi++).param("a", m.altText()).update();
        }
        audit.record(actor, tenantId, "PRODUCT_CREATED", "product", productId, null);
        return detail(tenantId, productId, false);
    }

    private void setInitialStock(UUID tenantId, UUID actor, UUID variantId, StockReq s) {
        if (s.quantity() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Stock cannot be negative");
        requireOwned("commerce.branches", s.branchId(), tenantId);
        jdbc.sql("INSERT INTO commerce.inventory_items (tenant_id, branch_id, variant_id, quantity_on_hand) VALUES (:t, :b, :v, :q)")
                .param("t", tenantId).param("b", s.branchId()).param("v", variantId).param("q", s.quantity()).update();
        jdbc.sql("""
                INSERT INTO commerce.inventory_movements (tenant_id, branch_id, variant_id, type, quantity_delta, reason, created_by)
                VALUES (:t, :b, :v, 'PURCHASE', :q, 'Initial stock', :a)
                """).param("t", tenantId).param("b", s.branchId()).param("v", variantId).param("q", s.quantity()).param("a", actor).update();
    }

    private void validateVariantsAgainstOptions(List<OptionReq> options, List<VariantReq> variants) {
        Set<String> optionNames = new HashSet<>();
        for (OptionReq o : options) {
            if (o.name() == null || o.name().isBlank() || o.values() == null || o.values().isEmpty())
                throw BusinessException.badRequest("VALIDATION_ERROR", "Each option needs a name and values");
            if (!optionNames.add(o.name())) throw BusinessException.badRequest("VALIDATION_ERROR", "Duplicate option " + o.name());
            if (new HashSet<>(o.values()).size() != o.values().size()) throw BusinessException.badRequest("VALIDATION_ERROR", "Duplicate values in " + o.name());
        }
        if (options.isEmpty() && variants.size() != 1)
            throw BusinessException.badRequest("VALIDATION_ERROR", "A product without options has exactly one variant");
        Set<String> seen = new HashSet<>();
        Set<String> skus = new HashSet<>();
        for (VariantReq v : variants) {
            if (v.sku() == null || v.sku().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "SKU is required");
            if (!skus.add(v.sku().trim())) throw BusinessException.conflict("SKU_TAKEN", "Duplicate SKU " + v.sku());
            if (v.priceMinor() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative");
            if (!seen.add(comboKey(options, v.optionValues())))
                throw BusinessException.badRequest("DUPLICATE_VARIANT", "Two variants have the same option combination");
        }
    }

    /** Canonical key of one exact option combination, in option order. Throws if values don't match the options. */
    private String comboKey(List<OptionReq> options, Map<String, String> chosen) {
        Map<String, String> c = chosen == null ? Map.of() : chosen;
        if (c.size() != options.size()) throw BusinessException.badRequest("VALIDATION_ERROR", "Each variant must pick one value for every option");
        List<String> parts = new ArrayList<>();
        for (OptionReq o : options) {
            String v = c.get(o.name());
            if (v == null || !o.values().contains(v)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid value for option " + o.name());
            parts.add(o.name() + "=" + v);
        }
        return String.join("|", parts);
    }

    @Transactional
    public Map<String, Object> updateProduct(UUID tenantId, UUID actor, UUID id, String name, String description, String shortDescription,
                                             String brand, UUID categoryId, String status) {
        requireOwned("commerce.products", id, tenantId);
        if (status != null && !Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");
        if (categoryId != null) requireOwned("commerce.categories", categoryId, tenantId);
        jdbc.sql("""
                UPDATE commerce.products SET name = coalesce(:n, name), description = coalesce(:d, description),
                       short_description = coalesce(:sd, short_description), brand = coalesce(:b, brand),
                       category_id = coalesce(:c, category_id), status = coalesce(:s, status), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("n", name).param("d", description).param("sd", shortDescription).param("b", brand).param("c", categoryId)
                .param("s", status).param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product", id, null);
        return detail(tenantId, id, false);
    }

    @Transactional
    public void updateVariant(UUID tenantId, UUID actor, UUID variantId, Long priceMinor, Long compareAt, String status) {
        requireOwned("commerce.product_variants", variantId, tenantId);
        if (priceMinor != null && priceMinor < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative");
        jdbc.sql("""
                UPDATE commerce.product_variants SET price_minor = coalesce(:p, price_minor),
                       compare_at_price_minor = coalesce(:c, compare_at_price_minor), status = coalesce(:s, status), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("p", priceMinor).param("c", compareAt).param("s", status).param("i", variantId).param("t", tenantId).update();
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product_variant", variantId, null);
    }

    /** Products are never hard-deleted (orders keep snapshots; history stays intact): DELETE archives. */
    @Transactional
    public void archive(UUID tenantId, UUID actor, UUID id) {
        requireOwned("commerce.products", id, tenantId);
        jdbc.sql("UPDATE commerce.products SET status = 'ARCHIVED', updated_at = now() WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "PRODUCT_ARCHIVED", "product", id, null);
    }

    // ---- reads ---------------------------------------------------------------------------------------

    public Map<String, Object> adminList(UUID tenantId, Page page, String q, String status) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        long total = jdbc.sql("""
                SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))
                AND (CAST(:q AS varchar) IS NULL OR name ILIKE CAST(:q AS varchar))
                """).param("t", tenantId).param("s", status).param("q", like).query(Long.class).single();
        var rows = jdbc.sql(listSql("p.status <> 'ARCHIVED' OR :s = 'ARCHIVED'", "(CAST(:s AS varchar) IS NULL OR p.status = CAST(:s AS varchar))"))
                .param("t", tenantId).param("s", status).param("q", like).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    public Map<String, Object> publicList(UUID tenantId, Page page, String q, String categorySlug) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        long total = jdbc.sql("""
                SELECT count(*) FROM commerce.products p LEFT JOIN commerce.categories c ON c.id = p.category_id
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND (CAST(:q AS varchar) IS NULL OR p.name ILIKE CAST(:q AS varchar))
                AND (CAST(:c AS varchar) IS NULL OR c.slug = CAST(:c AS varchar))
                """).param("t", tenantId).param("q", like).param("c", categorySlug).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT p.id, p.name, p.slug, p.short_description, p.brand, p.currency, p.category_id,
                  (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_price_minor,
                  (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_url,
                  EXISTS (SELECT 1 FROM commerce.product_variants v JOIN commerce.inventory_items i ON i.variant_id = v.id
                          JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                          WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND i.quantity_on_hand - i.quantity_reserved > 0) AS in_stock
                FROM commerce.products p LEFT JOIN commerce.categories c ON c.id = p.category_id
                WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND (CAST(:q AS varchar) IS NULL OR p.name ILIKE CAST(:q AS varchar))
                AND (CAST(:c AS varchar) IS NULL OR c.slug = CAST(:c AS varchar))
                ORDER BY p.created_at DESC LIMIT :lim OFFSET :off
                """).param("t", tenantId).param("q", like).param("c", categorySlug).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    private String listSql(String ignored1, String statusFilter) {
        return """
                SELECT p.id, p.name, p.slug, p.status, p.category_id, p.currency,
                  (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id) AS min_price_minor,
                  (SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) FROM commerce.product_variants v
                     JOIN commerce.inventory_items i ON i.variant_id = v.id WHERE v.product_id = p.id) AS available
                FROM commerce.products p WHERE p.tenant_id = :t AND (CAST(:q AS varchar) IS NULL OR p.name ILIKE CAST(:q AS varchar)) AND """ + statusFilter
                + " ORDER BY p.created_at DESC LIMIT :lim OFFSET :off";
    }

    /** One product with options, variants (and live availability) and media. publicOnly hides non-ACTIVE products. */
    public Map<String, Object> detail(UUID tenantId, UUID id, boolean publicOnly) {
        var p = jdbc.sql("SELECT * FROM commerce.products WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query().listOfRows()
                .stream().findFirst().filter(r -> !publicOnly || "ACTIVE".equals(r.get("status")))
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        return assemble(tenantId, p, publicOnly);
    }

    public Map<String, Object> detailBySlug(UUID tenantId, String slug) {
        var p = jdbc.sql("SELECT * FROM commerce.products WHERE slug = :s AND tenant_id = :t AND status = 'ACTIVE'").param("s", slug).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        return assemble(tenantId, p, true);
    }

    private Map<String, Object> assemble(UUID tenantId, Map<String, Object> p, boolean publicOnly) {
        UUID id = (UUID) p.get("id");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "slug", "description", "short_description", "brand", "status", "has_variants", "currency", "category_id"))
            out.put(Rows.camel(Map.of(k, "")).keySet().iterator().next(), p.get(k));
        out.put("options", jdbc.sql("""
                SELECT o.name, coalesce(array_agg(ov.value ORDER BY ov.sort_order) FILTER (WHERE ov.id IS NOT NULL), '{}') AS vals
                FROM commerce.product_options o LEFT JOIN commerce.product_option_values ov ON ov.option_id = o.id
                WHERE o.product_id = :p GROUP BY o.id, o.name, o.sort_order ORDER BY o.sort_order
                """).param("p", id).query((rs, n) -> Map.of("name", rs.getString(1), "values", List.of((String[]) rs.getArray(2).getArray()))).list());
        out.put("variants", Rows.camel(jdbc.sql("""
                SELECT v.id, v.sku, v.barcode, v.price_minor, v.compare_at_price_minor, v.currency, v.status, v.combo_key,
                  (SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) FROM commerce.inventory_items i
                     JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active WHERE i.variant_id = v.id) AS available
                FROM commerce.product_variants v WHERE v.product_id = :p AND v.tenant_id = :t AND (NOT :po OR v.status = 'ACTIVE') ORDER BY v.created_at
                """).param("p", id).param("t", tenantId).param("po", publicOnly).query().listOfRows()));
        out.put("media", Rows.camel(jdbc.sql("SELECT id, url, alt_text, sort_order FROM commerce.product_media WHERE product_id = :p ORDER BY sort_order")
                .param("p", id).query().listOfRows()));
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** 404s (never reveals existence) unless the row belongs to this tenant. */
    void requireOwned(String table, UUID id, UUID tenantId) {
        Long n = jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query(Long.class).single();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    static String slugify(String s) {
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return n.isEmpty() ? "item" : (n.length() > 80 ? n.substring(0, 80) : n);
    }

    private String uniqueSlug(String table, UUID tenantId, String base) {
        String slug = base;
        int i = 2;
        while (jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = :t AND slug = :s").param("t", tenantId).param("s", slug).query(Long.class).single() > 0)
            slug = base + "-" + i++;
        return slug;
    }
}
