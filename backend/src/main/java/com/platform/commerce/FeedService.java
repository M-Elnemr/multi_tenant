package com.platform.commerce;

import com.platform.files.FileService;
import com.platform.open.OpenUrls;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** One product list the shop can hand to Google Merchant Center, the Facebook/Instagram catalog and AI shopping assistants. Real stock, real prices, public pictures. */
@Service
public class FeedService {
    private final JdbcClient jdbc;
    private final FileService files;
    private final OpenUrls urls;

    public FeedService(JdbcClient jdbc, FileService files, OpenUrls urls) {
        this.jdbc = jdbc;
        this.files = files;
        this.urls = urls;
    }

    public Map<String, Object> feed(UUID tenantId) {
        String host = urls.hosts(List.of(tenantId)).get(tenantId);
        String base = urls.baseUrl(host);
        var shop = jdbc.sql("SELECT coalesce(nullif(sp.store_name, ''), t.name) AS name, coalesce(sp.short_description, '') AS description, t.default_currency AS currency FROM core.tenants t LEFT JOIN commerce.store_profiles sp ON sp.tenant_id = t.id WHERE t.id = :t")
                .param("t", tenantId).query().singleRow();
        var rows = jdbc.sql("""
                SELECT v.id AS variant_id, v.sku, v.barcode, v.price_minor, v.compare_at_price_minor, v.currency, v.combo_key, p.id AS product_id, p.name, p.slug, p.description, p.brand,
                       coalesce((SELECT sum(i.quantity_on_hand - i.quantity_reserved) FROM commerce.inventory_items i WHERE i.variant_id = v.id), 0) AS stock,
                       (SELECT m.file_id FROM commerce.product_media m WHERE m.product_id = p.id AND m.file_id IS NOT NULL AND (m.variant_id IS NULL OR m.variant_id = v.id) ORDER BY (m.variant_id = v.id) DESC NULLS LAST, m.sort_order LIMIT 1) AS file_id,
                       (SELECT m.url FROM commerce.product_media m WHERE m.product_id = p.id AND m.url IS NOT NULL ORDER BY m.sort_order LIMIT 1) AS external_image
                FROM commerce.product_variants v JOIN commerce.products p ON p.id = v.product_id
                WHERE v.tenant_id = :t AND v.status = 'ACTIVE' AND p.status = 'ACTIVE' ORDER BY p.created_at DESC, v.sku LIMIT 5000
                """).param("t", tenantId).query().listOfRows();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var r : rows) {
            String image = null;
            if (r.get("file_id") != null) {
                var loc = files.publicLocation(tenantId, (UUID) r.get("file_id"));
                image = loc.isEmpty() ? base + "/api/v1/files/" + r.get("file_id") + "/content?variant=original" : urls.image(host, loc.get("base"), loc.get("ext"), null, "original");
            } else if (r.get("external_image") != null) image = (String) r.get("external_image");
            String combo = r.get("combo_key") == null || ((String) r.get("combo_key")).isEmpty() ? null : ((String) r.get("combo_key")).replace("|", ", ").replace("=", ": ");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("sku"));
            m.put("itemGroupId", String.valueOf(r.get("product_id")));
            m.put("title", combo == null ? r.get("name") : r.get("name") + " - " + combo);
            m.put("description", r.get("description") == null || ((String) r.get("description")).isBlank() ? r.get("name") : r.get("description"));
            m.put("link", base + "/products/" + URLEncoder.encode((String) r.get("slug"), StandardCharsets.UTF_8));
            m.put("imageLink", image);
            m.put("price", fmt(((Number) r.get("price_minor")).longValue(), (String) r.get("currency")));
            if (r.get("compare_at_price_minor") != null && ((Number) r.get("compare_at_price_minor")).longValue() > ((Number) r.get("price_minor")).longValue()) m.put("compareAtPrice", fmt(((Number) r.get("compare_at_price_minor")).longValue(), (String) r.get("currency")));
            m.put("availability", ((Number) r.get("stock")).longValue() > 0 ? "in stock" : "out of stock");
            m.put("stock", Math.max(0, ((Number) r.get("stock")).longValue()));
            m.put("brand", r.get("brand") == null ? shop.get("name") : r.get("brand"));
            m.put("condition", "new");
            if (r.get("barcode") != null && !((String) r.get("barcode")).isBlank()) m.put("gtin", r.get("barcode"));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shop", shop.get("name"));
        out.put("description", shop.get("description"));
        out.put("url", base);
        out.put("currency", shop.get("currency"));
        out.put("items", items);
        return out;
    }

    private static String fmt(long minor, String currency) { return String.format(java.util.Locale.ROOT, "%.2f %s", minor / 100.0, currency); }
}
