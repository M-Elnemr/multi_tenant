package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Meta's product catalog (Commerce Manager): the products that feed the Shop tab of a Facebook Page, Instagram Shopping and catalog ads.
 * The owner connects it with the same "Connect with Facebook" login as the Page; the catalog id and a long-lived token are stored encrypted.
 * Products go in through the Catalog Batch API (one request per product, variants share an {@code item_group_id}); the shop's SKU is the catalog's {@code retailer_id}.
 * Meta checks a batch after it is accepted, so a batch is read back once and any rejected item is reported to the owner in Meta's own words.
 */
@Component
public class MetaCatalogAdapter implements ChannelAdapter {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private final String graphVersion;

    public MetaCatalogAdapter(@Value("${app.meta.graph-version:v21.0}") String graphVersion) { this.graphVersion = graphVersion; }

    @Override public String provider() { return "META_CATALOG"; }

    private String base() { return "https://graph.facebook.com/" + graphVersion; }

    private static Map<String, String> headers(Ctx c) { return Map.of("Authorization", "Bearer " + c.need("accessToken"), "Accept", "application/json"); }

    private static String catalog(Ctx c) {
        String id = c.need("catalogId");
        if (!id.matches("\\d{5,25}")) throw new HttpGateway.ApiException(400, "The catalog id is the long number shown in Commerce Manager");
        return id;
    }

    private HttpGateway.Res get(Ctx c, String path) { return c.http().call(Req.get(base() + path, headers(c), c.rateKey())); }

    private HttpGateway.Res post(Ctx c, String path, Object body) { return c.http().call(Req.json("POST", base() + path, headers(c), body, c.rateKey())); }

    private static String currency(Ctx c) { return "SA".equals(c.country()) ? "SAR" : "AE".equals(c.country()) ? "AED" : "EGP"; }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        JsonNode r = get(c, "/" + catalog(c) + "?fields=id,name,product_count").json();
        if (J.s(r, "id") == null) throw new HttpGateway.ApiException(401, "Meta did not return this catalog");
        return new Ext.AccountInfo(J.s(r, "id"), J.sOr(r, "Meta catalog", "name"), currency(c), null);
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        JsonNode r = get(c, "/" + catalog(c) + "/products?fields=id,retailer_id,name,description,price,image_url,availability,brand,url,retailer_product_group_id,inventory&limit=100"
                + (cursor == null ? "" : "&after=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8))).json();
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.path("data"))) {
            String retailer = J.sOr(p, J.s(p, "id"), "retailer_id");
            Integer qty = J.intOrNull(p, "inventory");
            if (qty == null) qty = "in stock".equals(J.s(p, "availability")) ? 1 : 0;
            String image = J.s(p, "image_url");
            out.add(new Ext.Product(J.sOr(p, retailer, "retailer_product_group_id"), J.s(p, "name"), J.s(p, "description"), J.s(p, "brand"), image == null ? List.of() : List.of(image), "ACTIVE",
                    List.of(new Ext.Variant(retailer, retailer, null, null, parsePrice(J.s(p, "price")), null, qty, null))));
        }
        String next = r.path("paging").path("next").isMissingNode() ? null : J.s(r, "paging", "cursors", "after");
        return new Ext.Page<>(out, next);
    }

    /** Meta writes prices as text with the currency ("199.00 EGP" or "EGP199.00"); the number is what matters. */
    static Long parsePrice(String text) {
        if (text == null) return null;
        Matcher m = NUMBER.matcher(text.replace(",", ""));
        return m.find() ? ChannelAdapter.minor(m.group(1)) : null;
    }

    private static String priceText(long minor, String currency) { return ChannelAdapter.amount(minor) + " " + currency; }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        if (d.link() == null || d.link().isBlank()) throw new HttpGateway.ApiException(400, "Meta needs the product's page address");
        if (d.images() == null || d.images().isEmpty()) throw new HttpGateway.ApiException(400, "Meta needs at least one picture of the product");
        String cur = currency(c);
        String group = java.util.UUID.nameUUIDFromBytes(d.link().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();   // stable per product: variants added later join the same group
        ArrayNode requests = HttpGateway.JSON.createArrayNode();
        Map<String, String> ids = new LinkedHashMap<>();
        int n = 0;
        for (Ext.VariantDraft v : d.variants()) {
            n++;
            String id = v.sku() == null || v.sku().isBlank() ? "ELM-" + Integer.toHexString((d.title() + n).hashCode()) : v.sku().trim();
            ids.put(v.sku(), id);
        }
        n = 0;
        for (Ext.VariantDraft v : d.variants()) {
            n++;
            ObjectNode data = HttpGateway.JSON.createObjectNode();
            data.put("id", ids.get(v.sku()));
            data.put("title", titleOf(d.title(), v.options()));
            data.put("description", d.description() == null || d.description().isBlank() ? d.title() : clip(d.description(), 5000));
            data.put("availability", v.quantity() > 0 ? "in stock" : "out of stock");
            data.put("condition", "new");
            applyPrice(data, v.priceMinor(), v.compareAtMinor(), cur);
            data.put("link", d.link());
            data.put("image_link", d.images().get(0));
            if (d.images().size() > 1) data.put("additional_image_link", String.join(",", d.images().subList(1, Math.min(d.images().size(), 11))));
            data.put("brand", d.brand() == null || d.brand().isBlank() ? "-" : d.brand());
            data.put("quantity_to_sell_on_facebook", Math.max(0, v.quantity()));
            data.put("item_group_id", group);
            if (v.barcode() != null && !v.barcode().isBlank()) data.put("gtin", v.barcode());
            if (v.options() != null) v.options().forEach((k, val) -> {
                String low = k == null ? "" : k.toLowerCase();
                if (low.contains("color") || low.contains("colour") || k.contains("لون")) data.put("color", val);
                else if (low.contains("size") || k.contains("مقاس")) data.put("size", val);
            });
            ObjectNode req = requests.addObject();
            req.put("method", "UPDATE");
            req.set("data", data);
        }
        batch(c, requests);
        return new Ext.Created(group, ids);
    }

    private static void applyPrice(ObjectNode data, long price, Long compare, String cur) {
        if (compare != null && compare > price) {   // the catalog shows the old price struck through and the new one as the sale price
            data.put("price", priceText(compare, cur));
            data.put("sale_price", priceText(price, cur));
        } else {
            data.put("price", priceText(price, cur));
        }
    }

    private static String titleOf(String title, Map<String, String> options) {
        if (options == null || options.isEmpty()) return clip(title, 150);
        return clip(title + " - " + String.join(" / ", options.values()), 150);
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        String retailer = variantId != null && !variantId.isBlank() ? variantId : sku;
        if (retailer == null || retailer.isBlank()) throw new HttpGateway.ApiException(400, "This item has no id on Meta");
        ObjectNode data = HttpGateway.JSON.createObjectNode();
        data.put("id", retailer);
        if (priceMinor != null) {
            applyPrice(data, priceMinor, compareAtMinor, currency(c));
            if (compareAtMinor == null || compareAtMinor <= priceMinor) data.put("sale_price", "");   // an empty value clears a sale price set earlier
        }
        if (quantity != null) {
            data.put("availability", quantity > 0 ? "in stock" : "out of stock");
            data.put("quantity_to_sell_on_facebook", Math.max(0, quantity));
        }
        ArrayNode requests = HttpGateway.JSON.createArrayNode();
        ObjectNode req = requests.addObject();
        req.put("method", "UPDATE");
        req.set("data", data);
        batch(c, requests);
    }

    /** Sends the batch and reads its status once: Meta validates after accepting, and a rejected item must not look published. */
    private void batch(Ctx c, ArrayNode requests) {
        ObjectNode body = HttpGateway.JSON.createObjectNode();
        body.put("item_type", "PRODUCT_ITEM");
        body.put("allow_upsert", true);
        body.set("requests", requests);
        JsonNode res = post(c, "/" + catalog(c) + "/items_batch", body).json();
        JsonNode h0 = res.path("handles").path(0);
        String handle = h0.isMissingNode() || h0.isNull() ? null : h0.asText();
        if (handle == null) return;
        JsonNode st = get(c, "/" + catalog(c) + "/check_batch_request_status?handle=" + java.net.URLEncoder.encode(handle, java.nio.charset.StandardCharsets.UTF_8) + "&load_ids_of_invalid_requests=true").json();
        for (JsonNode s : J.list(st.path("data"))) {
            int errors = s.path("errors_total_count").asInt(0);
            if (errors > 0) {
                JsonNode first = s.path("errors").path(0);
                String why = J.sOr(first, "Meta rejected the product", "message");
                throw new HttpGateway.ApiException(422, why);
            }
        }
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, java.time.Instant since, String cursor) { return new Ext.Page<>(List.of(), null); }
}
