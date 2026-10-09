package com.platform.commerce;

import com.platform.audit.AuditService;
import com.platform.billing.EntitlementService;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
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
    /** attribute: COLOR or SIZE for the standard options (sizeScale picks which size list); empty for a custom option such as "capacity". */
    public record OptionReq(String name, List<String> values, String attribute, String sizeScale) {
        public OptionReq(String name, List<String> values) { this(name, values, null, null); }
    }
    public record StockReq(UUID branchId, int quantity) {}
    public record VariantReq(String sku, long priceMinor, Long compareAtPriceMinor, String barcode, Integer weightGrams,
                             Map<String, String> optionValues, List<StockReq> stock) {}
    public record MediaReq(String url, UUID fileId, String altText) {}
    /** The category is always one of the platform's standard categories: send taxonomyId (or its slug). audience = MEN/WOMEN/BOYS/GIRLS/BABY/ALL. */
    public record ProductReq(String name, String description, String shortDescription, Integer taxonomyId, String taxonomySlug, String brand,
                             String audience, String condition, String status, List<OptionReq> options, List<VariantReq> variants, List<MediaReq> media) {}

    private final JdbcClient jdbc;
    private final EntitlementService ent;
    private final AuditService audit;
    private final com.platform.files.FileService files;
    private final TaxonomyService taxonomy;

    public CatalogService(JdbcClient jdbc, EntitlementService ent, AuditService audit, com.platform.files.FileService files, TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
        this.files = files;
        this.jdbc = jdbc;
        this.ent = ent;
        this.audit = audit;
    }

    /** Merchandising fields edited separately from the core product (tags, badge, specs table, size guide, featured, order). */
    @Transactional
    public Map<String, Object> updateExtras(UUID tenantId, UUID actor, UUID id, Map<String, Object> f) {
        requireOwned("commerce.products", id, tenantId);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String badge = f.get("badge") instanceof String b ? b.trim().toUpperCase(Locale.ROOT) : null;
        if (badge != null && !Set.of("", "NEW", "SALE", "BEST_SELLER", "LIMITED").contains(badge)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid badge");
        String tagsCsv = f.get("tags") instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).limit(15).toList()) : null;
        String specs = null;
        try { if (f.get("specs") instanceof List<?> l) specs = mapper.writeValueAsString(l.size() > 40 ? l.subList(0, 40) : l); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid specs"); }
        jdbc.sql("""
                UPDATE commerce.products SET badge = coalesce(:b, badge), tags = CASE WHEN CAST(:tg AS varchar) IS NULL THEN tags ELSE string_to_array(NULLIF(CAST(:tg AS varchar), ''), ',') END,
                  specs = coalesce(CAST(:sp AS jsonb), specs), size_guide = coalesce(:sg, size_guide), is_featured = coalesce(:ft, is_featured),
                  sort_order = coalesce(:so, sort_order), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("b", badge).param("tg", tagsCsv).param("sp", specs).param("sg", f.get("sizeGuide") instanceof String sg ? sg : null)
                .param("ft", f.get("isFeatured") instanceof Boolean ft ? ft : null).param("so", f.get("sortOrder") instanceof Number so ? so.intValue() : null)
                .param("i", id).param("t", tenantId).update();
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product", id, null);
        return detail(tenantId, id, false);
    }

    // ---- products -----------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createProduct(UUID tenantId, UUID actor, ProductReq r) {
        if (r.name() == null || r.name().isBlank()) throw BusinessException.badRequest("VALIDATION_ERROR", "Product name is required");
        if (r.variants() == null || r.variants().isEmpty()) throw BusinessException.badRequest("VALIDATION_ERROR", "At least one variant is required");
        long existing = jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND status <> 'ARCHIVED'").param("t", tenantId).query(Long.class).single();
        ent.requireCapacity(tenantId, "max_products", existing);
        String status = r.status() == null ? "ACTIVE" : r.status();
        if (!Set.of("DRAFT", "ACTIVE").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");
        Map<String, Object> node = taxonomy.pick(r.taxonomyId(), r.taxonomySlug(), false);
        if (node == null && "ACTIVE".equals(status)) throw BusinessException.badRequest("CATEGORY_REQUIRED", "Choose a category from the list");
        String audience = audience(r.audience(), node, "ACTIVE".equals(status));
        String condition = condition(r.condition());

        Canon canon = canonicalize(r.options() == null ? List.of() : r.options(), r.variants(), node);
        List<OptionReq> options = canon.options();
        List<VariantReq> variants = canon.variants();
        validateVariantsAgainstOptions(options, variants);

        UUID productId = jdbc.sql("""
                INSERT INTO commerce.products (tenant_id, taxonomy_id, name, slug, description, short_description, brand, status, has_variants, audience, item_condition)
                VALUES (:t, :c, :n, :s, :d, :sd, :b, :st, :hv, :au, :co) RETURNING id
                """).param("t", tenantId).param("c", node == null ? null : node.get("id")).param("au", audience).param("co", condition).param("n", r.name().trim())
                .param("s", uniqueSlug("commerce.products", tenantId, slugify(r.name())))
                .param("d", r.description() == null ? "" : r.description()).param("sd", r.shortDescription()).param("b", r.brand())
                .param("st", status).param("hv", !options.isEmpty()).query(UUID.class).single();

        Map<String, UUID> valueIds = new LinkedHashMap<>();   // "Option=Value" -> option_value id
        int oi = 0;
        for (OptionReq o : options) {
            UUID optionId = jdbc.sql("INSERT INTO commerce.product_options (product_id, name, sort_order, attribute_code, size_scale) VALUES (:p, :n, :o, :ac, :sc) RETURNING id")
                    .param("p", productId).param("n", o.name()).param("o", oi++).param("ac", o.attribute() == null ? "" : o.attribute()).param("sc", o.sizeScale() == null ? "" : o.sizeScale()).query(UUID.class).single();
            int vi = 0;
            for (String v : o.values()) {
                UUID vid = jdbc.sql("INSERT INTO commerce.product_option_values (option_id, value, sort_order, value_code) VALUES (:o, :v, :s, :vc) RETURNING id")
                        .param("o", optionId).param("v", v).param("s", vi++).param("vc", canon.codes().getOrDefault(o.name() + "=" + v, "")).query(UUID.class).single();
                valueIds.put(o.name() + "=" + v, vid);
            }
        }
        for (VariantReq v : variants) {
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
            for (MediaReq m : r.media()) insertMedia(tenantId, productId, m, mi++);
        }
        audit.record(actor, tenantId, "PRODUCT_CREATED", "product", productId, null);
        return detail(tenantId, productId, false);
    }

    private void insertMedia(UUID tenantId, UUID productId, MediaReq m, int order) {
        String url = m.url();
        if (m.fileId() != null) {
            files.requireReady(tenantId, m.fileId(), java.util.Set.of("PRODUCT_IMAGE"), null);
            url = "/api/v1/files/" + m.fileId() + "/content";
        }
        if (url == null) throw BusinessException.badRequest("VALIDATION_ERROR", "Media needs a fileId or url");
        var loc = m.fileId() == null ? java.util.Map.<String, String>of() : files.publicLocation(tenantId, m.fileId());
        jdbc.sql("INSERT INTO commerce.product_media (tenant_id, product_id, file_id, url, sort_order, alt_text, media_base, media_ext) VALUES (:t, :p, :f, :u, :o, :a, :mb, :me)")
                .param("t", tenantId).param("p", productId).param("f", m.fileId()).param("u", url).param("o", order).param("a", m.altText()).param("mb", loc.get("base")).param("me", loc.get("ext")).update();
    }

    /** Pictures can be added, removed and re-ordered after a product exists (the first one is the main picture). */
    @Transactional
    public Map<String, Object> addMedia(UUID tenantId, UUID actor, UUID productId, MediaReq m) {
        requireOwned("commerce.products", productId, tenantId);
        if (jdbc.sql("SELECT count(*) FROM commerce.product_media WHERE product_id = :p").param("p", productId).query(Long.class).single() >= 10)
            throw BusinessException.badRequest("TOO_MANY_IMAGES", "Up to 10 pictures per product");
        int next = jdbc.sql("SELECT coalesce(max(sort_order), -1) + 1 FROM commerce.product_media WHERE product_id = :p").param("p", productId).query(Integer.class).single();
        insertMedia(tenantId, productId, m, next);
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product", productId, null);
        return detail(tenantId, productId, false);
    }

    @Transactional
    public Map<String, Object> removeMedia(UUID tenantId, UUID actor, UUID productId, UUID mediaId) {
        requireOwned("commerce.products", productId, tenantId);
        jdbc.sql("DELETE FROM commerce.product_media WHERE id = :m AND product_id = :p AND tenant_id = :t").param("m", mediaId).param("p", productId).param("t", tenantId).update();
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product", productId, null);
        return detail(tenantId, productId, false);
    }

    /** `ids` lists the picture ids in the wanted order; any not listed keep their place after the listed ones. */
    @Transactional
    public Map<String, Object> reorderMedia(UUID tenantId, UUID actor, UUID productId, List<UUID> ids) {
        requireOwned("commerce.products", productId, tenantId);
        int i = 0;
        for (UUID id : ids) jdbc.sql("UPDATE commerce.product_media SET sort_order = :o WHERE id = :m AND product_id = :p AND tenant_id = :t").param("o", i++).param("m", id).param("p", productId).param("t", tenantId).update();
        jdbc.sql("UPDATE commerce.product_media SET sort_order = sort_order + :o WHERE product_id = :p AND tenant_id = :t AND NOT (id = ANY(string_to_array(:ids, ',')::uuid[]))")
                .param("o", ids.size()).param("p", productId).param("t", tenantId).param("ids", String.join(",", ids.stream().map(UUID::toString).toList())).update();
        audit.record(actor, tenantId, "PRODUCT_UPDATED", "product", productId, null);
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

    String audience(String raw, Map<String, Object> node, boolean active) {
        String a = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!a.isEmpty() && !TaxonomyService.AUDIENCES.contains(a)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid value for: for whom");
        if (a.isEmpty() && active && node != null && Boolean.TRUE.equals(node.get("applies_audience"))) throw BusinessException.badRequest("AUDIENCE_REQUIRED", "Choose who this product is for (men, women, kids...)");
        return a;
    }

    String condition(String raw) {
        String c = raw == null || raw.isBlank() ? "NEW" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TaxonomyService.CONDITIONS.contains(c)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid condition");
        return c;
    }

    private record Canon(List<OptionReq> options, List<VariantReq> variants, Map<String, String> codes) {}

    private static final Map<String, String> STANDARD_OPTION_NAMES = Map.ofEntries(Map.entry("اللون", "COLOR"), Map.entry("لون", "COLOR"), Map.entry("color", "COLOR"), Map.entry("colour", "COLOR"),
            Map.entry("المقاس", "SIZE"), Map.entry("مقاس", "SIZE"), Map.entry("size", "SIZE"));

    /**
     * Colour and size are standard: whatever the client wrote (a code, Arabic or English name, odd spelling) becomes the standard value, or the request is refused.
     * Variants' option values are rewritten the same way so they keep matching their options. Other (custom) options are left as they are.
     */
    private Canon canonicalize(List<OptionReq> options, List<VariantReq> variants, Map<String, Object> node) {
        List<OptionReq> outOptions = new ArrayList<>();
        Map<String, String> nameMap = new HashMap<>();                 // name as sent -> canonical name
        Map<String, Map<String, String>> valueMap = new HashMap<>();   // name as sent -> (value as sent -> canonical value)
        Map<String, String> codes = new HashMap<>();
        for (OptionReq o : options) {
            String sent = o.name() == null ? "" : o.name().trim();
            String attr = o.attribute() == null ? "" : o.attribute().trim().toUpperCase(Locale.ROOT);
            if (attr.isEmpty()) attr = STANDARD_OPTION_NAMES.getOrDefault(StoreSearchService.norm(sent), "");
            if (!attr.isEmpty() && !Set.of("COLOR", "SIZE").contains(attr)) throw BusinessException.badRequest("VALIDATION_ERROR", "Unknown option type");
            if (attr.isEmpty() || o.values() == null) { outOptions.add(o); continue; }
            String scale = "";
            if (attr.equals("SIZE")) {
                scale = o.sizeScale() == null ? "" : o.sizeScale().trim().toUpperCase(Locale.ROOT);
                if (scale.isEmpty()) {      // pick the first size list that contains every value the client sent
                    List<String> order = new ArrayList<>(); if (node != null && !((String) node.get("size_scales")).isEmpty()) order.addAll(List.of(((String) node.get("size_scales")).split(",")));
                    for (String sc : List.of("APPAREL", "KIDS", "SHOE", "BED")) if (!order.contains(sc)) order.add(sc);
                    for (String sc : order) if (o.values().stream().allMatch(v -> taxonomy.standardValue("SIZE", sc, v) != null)) { scale = sc; break; }
                    if (scale.isEmpty()) throw BusinessException.badRequest("INVALID_OPTION_VALUE", "Choose sizes from the standard list");
                }
            }
            String canonName = attr.equals("COLOR") ? "اللون" : "المقاس";
            Map<String, String> vm = new HashMap<>();
            List<String> canonValues = new ArrayList<>();
            for (String v : o.values()) {
                var std = taxonomy.standardValue(attr, scale, v);
                if (std == null) throw BusinessException.badRequest("INVALID_OPTION_VALUE", "\"" + v + "\" is not a standard " + (attr.equals("COLOR") ? "colour" : "size") + ": choose from the list");
                String canonValue = (String) std.get("name_ar");
                vm.put(v, canonValue);
                canonValues.add(canonValue);
                codes.put(canonName + "=" + canonValue, (String) std.get("code"));
            }
            nameMap.put(sent, canonName);
            valueMap.put(sent, vm);
            outOptions.add(new OptionReq(canonName, canonValues, attr, scale));
        }
        List<VariantReq> outVariants = new ArrayList<>();
        for (VariantReq v : variants) {
            Map<String, String> chosen = null;
            if (v.optionValues() != null) {
                chosen = new LinkedHashMap<>();
                for (var e : v.optionValues().entrySet()) {
                    String key = e.getKey() == null ? "" : e.getKey().trim();
                    String canonKey = nameMap.getOrDefault(key, e.getKey());
                    chosen.put(canonKey, valueMap.containsKey(key) ? valueMap.get(key).getOrDefault(e.getValue(), e.getValue()) : e.getValue());
                }
            }
            outVariants.add(new VariantReq(v.sku(), v.priceMinor(), v.compareAtPriceMinor(), v.barcode(), v.weightGrams(), chosen, v.stock()));
        }
        return new Canon(outOptions, outVariants, codes);
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
                                             String brand, Integer taxonomyId, String audience, String condition, String status) {
        requireOwned("commerce.products", id, tenantId);
        if (status != null && !Set.of("DRAFT", "ACTIVE", "ARCHIVED").contains(status)) throw BusinessException.badRequest("VALIDATION_ERROR", "Invalid status");
        var cur = jdbc.sql("SELECT status, taxonomy_id, audience FROM commerce.products WHERE id = :i").param("i", id).query().singleRow();
        Map<String, Object> node = taxonomy.pick(taxonomyId, null, false);
        Integer effectiveTaxonomy = node != null ? (Integer) node.get("id") : (Integer) cur.get("taxonomy_id");
        String effectiveStatus = status != null ? status : (String) cur.get("status");
        if ("ACTIVE".equals(effectiveStatus) && effectiveTaxonomy == null) throw BusinessException.badRequest("CATEGORY_REQUIRED", "Choose a category from the list");
        String aud = null;
        if (taxonomyId != null || audience != null || status != null) {
            Map<String, Object> checkNode = node != null ? node : effectiveTaxonomy == null ? null : taxonomy.pick(effectiveTaxonomy, null, true);
            aud = audience(audience != null ? audience : (String) cur.get("audience"), checkNode, "ACTIVE".equals(effectiveStatus));
        }
        jdbc.sql("""
                UPDATE commerce.products SET name = coalesce(:n, name), description = coalesce(:d, description),
                       short_description = coalesce(:sd, short_description), brand = coalesce(:b, brand),
                       taxonomy_id = coalesce(:c, taxonomy_id), audience = coalesce(:au, audience), item_condition = coalesce(:co, item_condition),
                       status = coalesce(:s, status), updated_at = now()
                WHERE id = :i AND tenant_id = :t
                """).param("n", name).param("d", description).param("sd", shortDescription).param("b", brand).param("c", node == null ? null : node.get("id")).param("au", aud)
                .param("co", condition == null || condition.isBlank() ? null : condition(condition)).param("s", status).param("i", id).param("t", tenantId).update();
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

    private String listSql(String ignored1, String statusFilter) {
        return """
                SELECT p.id, p.name, p.slug, p.status, p.taxonomy_id, p.currency,
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
        for (String k : List.of("id", "name", "slug", "description", "short_description", "brand", "status", "has_variants", "currency", "taxonomy_id", "audience", "item_condition", "badge", "size_guide", "is_featured"))
            out.put(Rows.camel(Map.of(k, "")).keySet().iterator().next(), p.get(k));
        out.put("tags", p.get("tags") instanceof java.sql.Array arr ? sqlArray(arr) : List.of());
        out.put("specs", Rows.jsonList(p.get("specs") == null ? null : p.get("specs").toString()));
        List<Map<String, Object>> options = new ArrayList<>(jdbc.sql("""
                SELECT o.name, o.attribute_code, o.size_scale, coalesce(array_agg(ov.value ORDER BY ov.sort_order) FILTER (WHERE ov.id IS NOT NULL), '{}') AS vals
                FROM commerce.product_options o LEFT JOIN commerce.product_option_values ov ON ov.option_id = o.id
                WHERE o.product_id = :p GROUP BY o.id, o.name, o.attribute_code, o.size_scale, o.sort_order ORDER BY o.sort_order
                """).param("p", id).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString(1));
                    m.put("attribute", rs.getString(2));
                    m.put("sizeScale", rs.getString(3));
                    m.put("values", List.of((String[]) rs.getArray(4).getArray()));
                    String attr = rs.getString(2);
                    m.put("labelAr", "COLOR".equals(attr) ? "اللون" : "SIZE".equals(attr) ? "المقاس" : rs.getString(1));
                    m.put("labelEn", "COLOR".equals(attr) ? "Color" : "SIZE".equals(attr) ? "Size" : rs.getString(1));
                    return m;
                }).list());
        // standard values carry their English name and colour swatch so the storefront can show them in either language
        var meta = jdbc.sql("""
                SELECT o.name, ov.value, a.name_ar, a.name_en, a.hex FROM commerce.product_options o
                JOIN commerce.product_option_values ov ON ov.option_id = o.id
                JOIN commerce.attribute_values a ON a.attr = o.attribute_code AND a.scale = o.size_scale AND a.code = ov.value_code
                WHERE o.product_id = :p AND o.attribute_code <> '' AND ov.value_code <> ''
                """).param("p", id).query().listOfRows();
        for (var o : options) {
            List<Map<String, Object>> vm = new ArrayList<>();
            for (var m : meta) if (m.get("name").equals(o.get("name"))) vm.add(Map.of("value", m.get("value"), "nameAr", m.get("name_ar"), "nameEn", m.get("name_en"), "hex", m.get("hex") == null ? "" : m.get("hex")));
            o.put("valueMeta", vm);
        }
        out.put("options", options);
        out.put("taxonomy", taxonomyPath(p.get("taxonomy_id")));
        out.put("variants", Rows.camel(jdbc.sql("""
                SELECT v.id, v.sku, v.barcode, v.price_minor, v.compare_at_price_minor, v.currency, v.status, v.combo_key,
                  (SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) FROM commerce.inventory_items i
                     JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active WHERE i.variant_id = v.id) AS available
                FROM commerce.product_variants v WHERE v.product_id = :p AND v.tenant_id = :t AND (NOT :po OR v.status = 'ACTIVE') ORDER BY v.created_at
                """).param("p", id).param("t", tenantId).param("po", publicOnly).query().listOfRows()));
        out.put("media", Rows.camel(jdbc.sql("SELECT id, url, alt_text, sort_order, media_base, media_ext FROM commerce.product_media WHERE product_id = :p ORDER BY sort_order")
                .param("p", id).query().listOfRows()));
        return out;
    }

    private List<Map<String, Object>> taxonomyPath(Object taxonomyId) {
        if (taxonomyId == null) return List.of();
        return Rows.camel(jdbc.sql("SELECT a.id, a.slug, a.name_ar, a.name_en, a.level FROM commerce.taxonomy t JOIN commerce.taxonomy a ON t.path LIKE a.path || '%' WHERE t.id = :i ORDER BY a.level")
                .param("i", taxonomyId).query().listOfRows());
    }

    private static List<String> sqlArray(java.sql.Array a) {
        try { return List.of((String[]) a.getArray()); } catch (java.sql.SQLException e) { return List.of(); }
    }

    /** A few other products from the same category (or the whole shop when the category is empty). */
    public List<Map<String, Object>> related(UUID tenantId, UUID productId, int limit) {
        return Rows.camel(jdbc.sql("""
                SELECT p.id, p.name, p.slug, p.brand, p.currency, p.badge,
                  (SELECT min(v.price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE') AS min_price_minor,
                  (SELECT max(v.compare_at_price_minor) FROM commerce.product_variants v WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND v.compare_at_price_minor > v.price_minor) AS compare_at_minor,
                  (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_url,
                  (SELECT m.media_base FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_base,
                  (SELECT m.media_ext FROM commerce.product_media m WHERE m.product_id = p.id ORDER BY m.sort_order LIMIT 1) AS image_media_ext,
                  EXISTS (SELECT 1 FROM commerce.product_variants v JOIN commerce.inventory_items i ON i.variant_id = v.id WHERE v.product_id = p.id AND v.status = 'ACTIVE' AND i.quantity_on_hand - i.quantity_reserved > 0) AS in_stock
                FROM commerce.products p WHERE p.tenant_id = :t AND p.status = 'ACTIVE' AND p.id <> :i
                ORDER BY (p.taxonomy_id IS NOT DISTINCT FROM (SELECT taxonomy_id FROM commerce.products WHERE id = :i)) DESC, p.sold_count DESC, p.created_at DESC LIMIT :l
                """).param("t", tenantId).param("i", productId).param("l", Math.min(Math.max(limit, 1), 12)).query().listOfRows());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** 404s (never reveals existence) unless the row belongs to this tenant. */
    void requireOwned(String table, UUID id, UUID tenantId) {
        Long n = jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :i AND tenant_id = :t").param("i", id).param("t", tenantId).query(Long.class).single();
        if (n == 0) throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Not found");
    }

    static String slugify(String s) {
        // NFKC keeps Arabic letters such as أ whole (NFKD would split off the hamza mark and the slug would break into "ا-طفال"); diacritics and tatweel are dropped.
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKC).replaceAll("[\\u064B-\\u065F\\u0670\\u0640]", "").replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
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
