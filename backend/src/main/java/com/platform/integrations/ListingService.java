package com.platform.integrations;

import com.platform.audit.AuditService;
import com.platform.commerce.CatalogService;
import com.platform.open.OpenUrls;
import com.platform.shared.BusinessException;
import com.platform.shared.Page;
import com.platform.shared.Rows;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * A listing is one product (variant) on one channel with that channel's own price and quantity. This service lists them, edits them (pushing to the platform),
 * links them to the shop's products, publishes shop products to channels (with per-channel price and quantity) and brings channel products into the shop.
 */
@Service
public class ListingService {
    public record VariantOffer(UUID variantId, Long priceMinor, Long compareAtMinor, Integer quantity) {}
    public record Target(UUID accountId, List<VariantOffer> variants) {}
    public record PublishReq(UUID productId, List<Target> targets) {}

    private final JdbcClient jdbc;
    private final IntegrationService integrations;
    private final AuditService audit;
    private final OpenUrls urls;
    private final CatalogService catalog;

    public ListingService(JdbcClient jdbc, IntegrationService integrations, AuditService audit, OpenUrls urls, CatalogService catalog) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.audit = audit;
        this.urls = urls;
        this.catalog = catalog;
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    public Map<String, Object> list(UUID tenantId, UUID accountId, String q, String filter, Page page) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        String where = """
                l.tenant_id = :t AND (CAST(:a AS uuid) IS NULL OR l.account_id = CAST(:a AS uuid))
                AND (CAST(:q AS varchar) IS NULL OR l.title ILIKE CAST(:q AS varchar) OR l.external_sku ILIKE CAST(:q AS varchar))
                AND (CAST(:f AS varchar) IS NULL OR (:f = 'unlinked' AND l.variant_id IS NULL) OR (:f = 'linked' AND l.variant_id IS NOT NULL) OR (:f = 'error' AND l.status = 'ERROR') OR (:f = 'low' AND coalesce(l.quantity, 99) <= 3))
                """;
        long total = jdbc.sql("SELECT count(*) FROM integrations.listings l WHERE " + where).param("t", tenantId).param("a", accountId).param("q", like).param("f", blank(filter)).query(Long.class).single();
        var rows = jdbc.sql("""
                SELECT l.id, l.account_id, a.provider, a.country, l.variant_id, l.product_id, l.external_product_id, l.external_variant_id, l.external_sku, l.title, l.image_url, l.price_minor, l.compare_at_minor, l.quantity,
                       l.stock_sync, l.currency, l.status, l.last_error, l.last_synced_at, p.name AS shop_product_name, v.sku AS shop_sku,
                       (SELECT coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0) FROM commerce.inventory_items i WHERE i.variant_id = l.variant_id) AS shop_available
                FROM integrations.listings l JOIN integrations.accounts a ON a.id = l.account_id
                LEFT JOIN commerce.product_variants v ON v.id = l.variant_id LEFT JOIN commerce.products p ON p.id = l.product_id
                WHERE %s ORDER BY l.updated_at DESC, l.id LIMIT :lim OFFSET :off
                """.formatted(where)).param("t", tenantId).param("a", accountId).param("q", like).param("f", blank(filter)).param("lim", page.pageSize()).param("off", page.offset()).query().listOfRows();
        return page.wrap(Rows.camel(rows), total);
    }

    // ---- writing ------------------------------------------------------------------------------------------------

    public void upsert(UUID tenantId, UUID accountId, Ext.Product p, Ext.Variant v, String currency) {
        String title = v.title() == null || v.title().isBlank() ? p.title() : p.title() + " - " + v.title();
        jdbc.sql("""
                INSERT INTO integrations.listings (tenant_id, account_id, external_product_id, external_variant_id, external_sku, external_ref, title, image_url, price_minor, compare_at_minor, quantity, currency, status, last_synced_at)
                VALUES (:t, :a, :ep, :ev, :sku, :ref, :ti, :im, :pr, :cp, :q, :c, 'SYNCED', now())
                ON CONFLICT (account_id, external_product_id, external_variant_id) DO UPDATE SET external_sku = :sku, external_ref = coalesce(:ref, integrations.listings.external_ref), title = :ti, image_url = :im,
                  price_minor = :pr, compare_at_minor = :cp, quantity = :q, status = 'SYNCED', last_error = NULL, last_synced_at = now(), updated_at = now()
                """).param("t", tenantId).param("a", accountId).param("ep", p.id()).param("ev", v.id() == null ? "" : v.id()).param("sku", blank(v.sku())).param("ref", blank(v.inventoryRef())).param("ti", title.length() > 480 ? title.substring(0, 480) : title)
                .param("im", p.images() == null || p.images().isEmpty() ? null : p.images().get(0)).param("pr", v.priceMinor()).param("cp", v.compareAtMinor()).param("q", v.quantity()).param("c", currency).update();
    }

    /** Listings whose SKU equals a shop SKU are linked to it (links the owner made by hand are never changed). */
    public int linkBySku(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                UPDATE integrations.listings l SET variant_id = v.id, product_id = v.product_id, updated_at = now() FROM commerce.product_variants v
                WHERE l.account_id = :a AND l.tenant_id = :t AND l.variant_id IS NULL AND l.external_sku IS NOT NULL AND v.tenant_id = :t AND lower(v.sku) = lower(l.external_sku)
                """).param("a", accountId).param("t", tenantId).update();
    }

    private Map<String, Object> listing(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT l.*, a.provider FROM integrations.listings l JOIN integrations.accounts a ON a.id = l.account_id WHERE l.id = :i AND l.tenant_id = :t").param("i", id).param("t", tenantId)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Listing not found"));
    }

    public record Edit(Long priceMinor, Long compareAtMinor, Integer quantity, Boolean stockSync) {}

    /** Changes this channel's price/quantity: the platform is updated first, and only then is it saved here. */
    public Map<String, Object> edit(UUID tenantId, UUID actor, UUID id, Edit e) {
        var l = listing(tenantId, id);
        if (e.priceMinor() != null && e.priceMinor() < 0 || e.quantity() != null && e.quantity() < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price and quantity cannot be negative");
        boolean push = e.priceMinor() != null || e.quantity() != null || e.compareAtMinor() != null;
        if (push) pushOffer(l, e.priceMinor(), e.compareAtMinor(), e.quantity());
        jdbc.sql("""
                UPDATE integrations.listings SET price_minor = coalesce(CAST(:p AS bigint), price_minor),
                  compare_at_minor = CASE WHEN CAST(:cp AS bigint) IS NULL THEN compare_at_minor WHEN CAST(:cp AS bigint) = 0 THEN NULL ELSE CAST(:cp AS bigint) END, quantity = coalesce(CAST(:q AS int), quantity),
                  stock_sync = coalesce(CAST(:ss AS boolean), stock_sync), status = 'SYNCED', last_error = NULL, last_synced_at = CASE WHEN CAST(:push AS boolean) THEN now() ELSE last_synced_at END, updated_at = now() WHERE id = :i
                """).param("p", e.priceMinor()).param("cp", e.compareAtMinor()).param("q", e.quantity()).param("ss", e.stockSync()).param("push", push).param("i", id).update();
        audit.record(actor, tenantId, "LISTING_UPDATED", "integration_listing", id, null);
        return Rows.camel(jdbc.sql("SELECT id, price_minor, compare_at_minor, quantity, stock_sync, status, last_error, last_synced_at FROM integrations.listings WHERE id = :i").param("i", id).query().singleRow());
    }

    /** One update to the platform, with the failure recorded on the listing so the owner sees what to fix. */
    void pushOffer(Map<String, Object> l, Long price, Long compareAt, Integer qty) {
        UUID accountId = (UUID) l.get("account_id");
        var account = integrations.rowAny(accountId);
        try {
            integrations.adapter((String) account.get("provider")).updateOffer(integrations.ctxOf(account), (String) l.get("external_product_id"), (String) l.get("external_variant_id"), (String) l.get("external_sku"),
                    (String) l.get("external_ref"), price, compareAt, qty);
        } catch (HttpGateway.ApiException ex) {
            jdbc.sql("UPDATE integrations.listings SET status = 'ERROR', last_error = :m, updated_at = now() WHERE id = :i").param("m", IntegrationService.clip(ex.getMessage())).param("i", l.get("id")).update();
            throw new BusinessException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "CHANNEL_REJECTED", ex.getMessage());
        }
    }

    public Map<String, Object> link(UUID tenantId, UUID id, UUID variantId) {
        listing(tenantId, id);
        var v = jdbc.sql("SELECT id, product_id FROM commerce.product_variants WHERE id = :v AND tenant_id = :t").param("v", variantId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        jdbc.sql("UPDATE integrations.listings SET variant_id = :v, product_id = :p, updated_at = now() WHERE id = :i").param("v", variantId).param("p", v.get("product_id")).param("i", id).update();
        return Map.of("ok", true);
    }

    public void unlink(UUID tenantId, UUID id) {
        listing(tenantId, id);
        jdbc.sql("UPDATE integrations.listings SET variant_id = NULL, product_id = NULL, stock_sync = FALSE, updated_at = now() WHERE id = :i").param("i", id).update();
    }

    /** Listings that follow the shop's stock: pushes the shop's available quantity when it differs from what the channel has. */
    public void reconcileStock(int limit) {
        var rows = jdbc.sql("""
                SELECT l.id, l.account_id, l.external_product_id, l.external_variant_id, l.external_sku, l.external_ref, l.quantity,
                  (SELECT greatest(0, coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0)) FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active WHERE i.variant_id = l.variant_id) AS available
                FROM integrations.listings l JOIN integrations.accounts a ON a.id = l.account_id AND a.status = 'ACTIVE'
                WHERE l.stock_sync AND l.variant_id IS NOT NULL AND EXISTS (SELECT 1 FROM core.tenants tn WHERE tn.id = a.tenant_id AND tn.status NOT IN ('SUSPENDED','CANCELLED','ARCHIVED')) ORDER BY l.last_synced_at NULLS FIRST LIMIT :lim
                """).param("lim", limit).query().listOfRows();
        for (var l : rows) {
            int available = ((Number) l.get("available")).intValue();
            if (l.get("quantity") != null && ((Number) l.get("quantity")).intValue() == available) continue;
            try {
                pushOffer(l, null, null, available);
                jdbc.sql("UPDATE integrations.listings SET quantity = :q, status = 'SYNCED', last_error = NULL, last_synced_at = now() WHERE id = :i").param("q", available).param("i", l.get("id")).update();
            } catch (BusinessException ignored) { /* recorded on the listing */ }
        }
    }

    // ---- publish shop products to channels -------------------------------------------------------------------------

    /** Everything the "publish to" screen needs: the product's variants with their price and stock, the channels it can go to, and where it already is. */
    public Map<String, Object> plan(UUID tenantId, UUID productId) {
        var product = jdbc.sql("SELECT id, name FROM commerce.products WHERE id = :p AND tenant_id = :t").param("p", productId).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        var variants = Rows.camel(jdbc.sql("""
                SELECT v.id, v.sku, v.price_minor, v.compare_at_price_minor, v.combo_key, v.currency,
                  (SELECT greatest(0, coalesce(sum(i.quantity_on_hand - i.quantity_reserved), 0)) FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active WHERE i.variant_id = v.id) AS available
                FROM commerce.product_variants v WHERE v.product_id = :p AND v.tenant_id = :t AND v.status = 'ACTIVE' ORDER BY v.created_at
                """).param("p", productId).param("t", tenantId).query().listOfRows());
        var accounts = Rows.camel(jdbc.sql("""
                SELECT a.id, a.provider, a.country, a.label, a.status FROM integrations.accounts a WHERE a.tenant_id = :t AND a.kind IN ('MARKETPLACE', 'STORE_PLATFORM') AND a.status <> 'PAUSED' ORDER BY a.created_at
                """).param("t", tenantId).query().listOfRows());
        List<Map<String, Object>> out = new ArrayList<>();
        for (var a : accounts) {
            Providers.Def d = Providers.get((String) a.get("provider"));
            if (d == null || !(Providers.supports(d, "CREATE") || Providers.supports(d, "PUSH"))) continue;
            Map<String, Object> m = new LinkedHashMap<>(a);
            m.put("name", d.name());
            m.put("kind", d.kind());
            m.put("canCreate", Providers.supports(d, "CREATE"));
            m.put("listings", Rows.camel(jdbc.sql("SELECT id, variant_id, price_minor, compare_at_minor, quantity, status, last_error, stock_sync FROM integrations.listings WHERE account_id = :a AND product_id = :p")
                    .param("a", a.get("id")).param("p", productId).query().listOfRows()));
            out.add(m);
        }
        return Map.of("product", Rows.camel(product), "variants", variants, "accounts", out);
    }

    /** Publishes (or updates) the product on each chosen channel with that channel's own price and quantity; one channel failing never stops the others. */
    public List<Map<String, Object>> publish(UUID tenantId, UUID actor, PublishReq req) {
        var product = jdbc.sql("SELECT id, name, description, brand FROM commerce.products WHERE id = :p AND tenant_id = :t AND status <> 'ARCHIVED'").param("p", req.productId()).param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Product not found"));
        var options = jdbc.sql("SELECT name FROM commerce.product_options WHERE product_id = :p ORDER BY sort_order").param("p", req.productId()).query(String.class).list();
        var variantRows = jdbc.sql("SELECT id, sku, barcode, price_minor, compare_at_price_minor, combo_key FROM commerce.product_variants WHERE product_id = :p AND tenant_id = :t AND status = 'ACTIVE'")
                .param("p", req.productId()).param("t", tenantId).query().listOfRows();
        Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
        for (var v : variantRows) byId.put((UUID) v.get("id"), v);
        Map<UUID, Integer> stock = new LinkedHashMap<>();
        for (var r : jdbc.sql("""
                SELECT i.variant_id, greatest(0, sum(i.quantity_on_hand - i.quantity_reserved)) AS q FROM commerce.inventory_items i JOIN commerce.branches b ON b.id = i.branch_id AND b.is_active
                WHERE i.tenant_id = :t AND i.variant_id IN (:ids) GROUP BY i.variant_id
                """).param("t", tenantId).param("ids", byId.keySet()).query().listOfRows()) stock.put((UUID) r.get("variant_id"), ((Number) r.get("q")).intValue());
        List<String> images = productImages(tenantId, req.productId());
        List<Map<String, Object>> results = new ArrayList<>();
        for (Target t : req.targets() == null ? List.<Target>of() : req.targets()) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("accountId", t.accountId());
            try {
                var account = integrations.row(tenantId, t.accountId());
                Providers.Def d = Providers.get((String) account.get("provider"));
                res.put("provider", d.id());
                if (!Providers.supports(d, "CREATE") && !Providers.supports(d, "PUSH")) throw new HttpGateway.ApiException(400, d.name() + " cannot publish products yet");
                int done = publishTo(tenantId, actor, account, d, product, options, byId, stock, images, t);
                res.put("ok", true);
                res.put("published", done);
            } catch (HttpGateway.ApiException e) {
                res.put("ok", false);
                res.put("error", e.getMessage());
            } catch (BusinessException e) {
                res.put("ok", false);
                res.put("error", e.getMessage());
            }
            results.add(res);
        }
        return results;
    }

    private int publishTo(UUID tenantId, UUID actor, Map<String, Object> account, Providers.Def d, Map<String, Object> product, List<String> optionNames, Map<UUID, Map<String, Object>> variants,
                          Map<UUID, Integer> stock, List<String> images, Target t) {
        UUID accountId = (UUID) account.get("id");
        ChannelAdapter adapter = integrations.adapter(d.id());
        ChannelAdapter.Ctx ctx = integrations.ctxOf(account);
        String currency = Providers.currencyOf((String) account.get("country"));
        List<Ext.VariantDraft> toCreate = new ArrayList<>();
        Map<String, Map<String, Object>> byVariantSku = new LinkedHashMap<>();
        int done = 0;
        for (VariantOffer o : t.variants() == null ? List.<VariantOffer>of() : t.variants()) {
            var v = variants.get(o.variantId());
            if (v == null) continue;
            long price = o.priceMinor() != null ? o.priceMinor() : ((Number) v.get("price_minor")).longValue();
            Long compare = o.compareAtMinor() != null ? o.compareAtMinor() : v.get("compare_at_price_minor") == null ? null : ((Number) v.get("compare_at_price_minor")).longValue();
            int qty = o.quantity() != null ? Math.max(0, o.quantity()) : stock.getOrDefault(o.variantId(), 0);
            if (price < 0) throw BusinessException.badRequest("VALIDATION_ERROR", "Price cannot be negative");
            var existing = jdbc.sql("SELECT * FROM integrations.listings WHERE account_id = :a AND variant_id = :v").param("a", accountId).param("v", o.variantId()).query().listOfRows().stream().findFirst().orElse(null);
            if (existing != null) {   // already on this channel: update it
                pushOffer(existing, price, compare, qty);
                jdbc.sql("UPDATE integrations.listings SET price_minor = :p, compare_at_minor = :c, quantity = :q, status = 'SYNCED', last_error = NULL, last_synced_at = now(), updated_at = now() WHERE id = :i").param("p", price).param("c", compare).param("q", qty).param("i", existing.get("id")).update();
                done++;
                continue;
            }
            String sku = (String) v.get("sku");
            Map<String, String> opts = parseCombo((String) v.get("combo_key"));
            if (Providers.supports(d, "CREATE")) {
                toCreate.add(new Ext.VariantDraft(sku, (String) v.get("barcode"), opts, price, compare, qty));
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("variantId", o.variantId()); info.put("price", price); info.put("compare", compare); info.put("qty", qty);
                byVariantSku.put(sku, info);
            } else {   // the channel cannot create products (noon): link an existing channel product by SKU and set its price and stock
                try {
                    adapter.updateOffer(ctx, sku, sku, sku, null, price, compare, qty);
                } catch (HttpGateway.ApiException e) {
                    throw new HttpGateway.ApiException(e.status, e.getMessage());
                }
                insertListing(tenantId, accountId, o.variantId(), (UUID) product.get("id"), sku, sku, sku, (String) product.get("name"), images.isEmpty() ? null : images.get(0), price, compare, qty, currency);
                done++;
            }
        }
        if (!toCreate.isEmpty()) {
            Ext.ProductDraft draft = new Ext.ProductDraft((String) product.get("name"), (String) product.get("description"), (String) product.get("brand"), optionNames, images, null, toCreate);
            Ext.Created created = adapter.createProduct(ctx, draft);
            for (var e : byVariantSku.entrySet()) {
                String extVariant = created.variantIds().get(e.getKey());
                if (extVariant == null) continue;
                var info = e.getValue();
                insertListing(tenantId, accountId, (UUID) info.get("variantId"), (UUID) product.get("id"), created.productId(), extVariant, e.getKey(), (String) product.get("name"), images.isEmpty() ? null : images.get(0),
                        (Long) info.get("price"), (Long) info.get("compare"), (Integer) info.get("qty"), currency);
                done++;
            }
        }
        audit.record(actor, tenantId, "PRODUCT_PUBLISHED", "integration_account", accountId, "{\"provider\":\"" + d.id() + "\",\"variants\":" + done + "}");
        return done;
    }

    private void insertListing(UUID tenantId, UUID accountId, UUID variantId, UUID productId, String extProduct, String extVariant, String sku, String title, String image, Long price, Long compare, Integer qty, String currency) {
        jdbc.sql("""
                INSERT INTO integrations.listings (tenant_id, account_id, variant_id, product_id, external_product_id, external_variant_id, external_sku, title, image_url, price_minor, compare_at_minor, quantity, currency, status, last_synced_at)
                VALUES (:t, :a, :v, :p, :ep, :ev, :sku, :ti, :im, :pr, :cp, :q, :c, 'SYNCED', now())
                ON CONFLICT (account_id, external_product_id, external_variant_id) DO UPDATE SET variant_id = :v, product_id = :p, price_minor = :pr, compare_at_minor = :cp, quantity = :q, status = 'SYNCED', last_error = NULL, last_synced_at = now(), updated_at = now()
                """).param("t", tenantId).param("a", accountId).param("v", variantId).param("p", productId).param("ep", extProduct).param("ev", extVariant == null ? "" : extVariant).param("sku", sku).param("ti", title).param("im", image)
                .param("pr", price).param("cp", compare).param("q", qty).param("c", currency).update();
    }

    static Map<String, String> parseCombo(String combo) {
        Map<String, String> m = new LinkedHashMap<>();
        if (combo == null || combo.isBlank()) return m;
        for (String part : combo.split("\\|")) { int i = part.indexOf('='); if (i > 0) m.put(part.substring(0, i), part.substring(i + 1)); }
        return m;
    }

    /** Public addresses of the product's pictures: the platforms download them from there. */
    List<String> productImages(UUID tenantId, UUID productId) {
        var host = urls.hosts(List.of(tenantId)).get(tenantId);
        List<String> out = new ArrayList<>();
        for (var m : jdbc.sql("SELECT url, media_base, media_ext FROM commerce.product_media WHERE product_id = :p ORDER BY sort_order LIMIT 8").param("p", productId).query().listOfRows()) {
            String u = urls.image(host, (String) m.get("media_base"), (String) m.get("media_ext"), (String) m.get("url"), "original");
            if (u != null) out.add(u);
        }
        return out;
    }

    // ---- bring channel products into the shop ------------------------------------------------------------------------

    /** Creates shop products (as drafts, to review) from channel products that are not in the shop yet, and links them. */
    public Map<String, Object> importToShop(UUID tenantId, UUID actor, UUID accountId, List<UUID> listingIds) {
        integrations.row(tenantId, accountId);
        var rows = jdbc.sql("SELECT * FROM integrations.listings WHERE account_id = :a AND tenant_id = :t AND variant_id IS NULL AND id IN (:ids) ORDER BY external_product_id, id").param("a", accountId).param("t", tenantId).param("ids", listingIds).query().listOfRows();
        UUID branch = jdbc.sql("SELECT id FROM commerce.branches WHERE tenant_id = :t AND is_active ORDER BY created_at LIMIT 1").param("t", tenantId).query(UUID.class).optional().orElse(null);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (var r : rows) grouped.computeIfAbsent((String) r.get("external_product_id"), k -> new ArrayList<>()).add(r);
        int created = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        for (var g : grouped.values()) {
            try {
                String title = (String) g.get(0).get("title");
                boolean multi = g.size() > 1;
                List<CatalogService.VariantReq> variants = new ArrayList<>();
                List<String> values = new ArrayList<>();
                int n = 1;
                for (var l : g) {
                    String sku = l.get("external_sku") == null || ((String) l.get("external_sku")).isBlank() ? "CH-" + ((UUID) l.get("id")).toString().substring(0, 8) : (String) l.get("external_sku");
                    String value = multi ? "Option " + n : null;
                    if (multi) values.add(value);
                    long price = l.get("price_minor") == null ? 0 : ((Number) l.get("price_minor")).longValue();
                    Long compare = l.get("compare_at_minor") == null ? null : ((Number) l.get("compare_at_minor")).longValue();
                    int qty = l.get("quantity") == null ? 0 : ((Number) l.get("quantity")).intValue();
                    variants.add(new CatalogService.VariantReq(sku, price, compare, null, null, multi ? Map.of("Option", value) : null, branch == null || qty == 0 ? null : List.of(new CatalogService.StockReq(branch, qty))));
                    n++;
                }
                String image = (String) g.get(0).get("image_url");
                var p = catalog.createProduct(tenantId, actor, new CatalogService.ProductReq(title, "", null, null, null, null, "", "NEW", "DRAFT", multi ? List.of(new CatalogService.OptionReq("Option", values)) : List.of(), variants,
                        image == null ? List.of() : List.of(new CatalogService.MediaReq(image, null, null, null))));
                UUID productId = (UUID) p.get("id");
                @SuppressWarnings("unchecked") List<Map<String, Object>> created2 = (List<Map<String, Object>>) p.get("variants");
                for (var l : g) {
                    String sku = l.get("external_sku") == null ? null : (String) l.get("external_sku");
                    for (var v : created2) if (sku != null && sku.equalsIgnoreCase((String) v.get("sku")) || sku == null && created2.size() == 1)
                        jdbc.sql("UPDATE integrations.listings SET variant_id = :v, product_id = :p, updated_at = now() WHERE id = :i").param("v", v.get("id")).param("p", productId).param("i", l.get("id")).update();
                }
                created++;
            } catch (RuntimeException e) {
                skipped++;
                errors.add(e.getMessage());
            }
        }
        audit.record(actor, tenantId, "CHANNEL_PRODUCTS_IMPORTED", "integration_account", accountId, "{\"created\":" + created + "}");
        return Map.of("created", created, "skipped", skipped, "errors", errors.stream().limit(5).toList());
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
