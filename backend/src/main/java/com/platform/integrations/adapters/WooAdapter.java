package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import com.platform.integrations.UrlGuard;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** WooCommerce REST API v3 with a consumer key/secret per store. A simple product's variant id is the product id; a variation's is its own id. */
@Component
public class WooAdapter implements ChannelAdapter {
    @Override public String provider() { return "WOOCOMMERCE"; }

    private static String base(Ctx c) { return UrlGuard.requirePublicHttps(c.need("siteUrl")) + "/wp-json/wc/v3"; }

    private static Map<String, String> headers(Ctx c) {
        String auth = Base64.getEncoder().encodeToString((c.need("consumerKey") + ":" + c.need("consumerSecret")).getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + auth, "Accept", "application/json");
    }

    private HttpGateway.Res get(Ctx c, String path) { return c.http().call(Req.get(base(c) + path, headers(c), c.rateKey())); }

    private HttpGateway.Res send(Ctx c, String method, String path, Object body) { return c.http().call(Req.json(method, base(c) + path, headers(c), body, c.rateKey())); }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        get(c, "/products?per_page=1");   // 401 here means the key or secret is wrong
        String currency = currencyOf(c);
        return new Ext.AccountInfo(UrlGuard.requirePublicHttps(c.need("siteUrl")).replaceFirst("^https://", ""), UrlGuard.requirePublicHttps(c.need("siteUrl")).replaceFirst("^https://", ""), currency, null);
    }

    private String currencyOf(Ctx c) {
        try { return J.sOr(get(c, "/data/currencies/current").json(), com.platform.integrations.Providers.currencyOf(c.country()), "code"); } catch (HttpGateway.ApiException e) { return com.platform.integrations.Providers.currencyOf(c.country()); }
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        HttpGateway.Res r = get(c, "/products?per_page=50&page=" + page + "&status=any");
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.json())) {
            List<Ext.Variant> vs = new ArrayList<>();
            if ("variable".equals(J.s(p, "type"))) {
                for (JsonNode v : J.list(get(c, "/products/" + J.s(p, "id") + "/variations?per_page=100").json())) {
                    List<String> opts = new ArrayList<>();
                    for (JsonNode a : J.list(v.path("attributes"))) opts.add(J.s(a, "option"));
                    vs.add(variant(v, String.join(" / ", opts)));
                }
            } else vs.add(variant(p, null));
            List<String> images = new ArrayList<>();
            for (JsonNode i : J.list(p.path("images"))) { String src = J.s(i, "src"); if (src != null) images.add(src); }
            out.add(new Ext.Product(J.s(p, "id"), J.s(p, "name"), J.plain(J.s(p, "description")), J.s(p, "brands", "0", "name"), images, J.s(p, "status"), vs));
        }
        int total = Integer.parseInt(r.header("X-WP-TotalPages") == null ? "1" : r.header("X-WP-TotalPages").trim());
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }

    private static Ext.Variant variant(JsonNode v, String title) {
        boolean sale = !J.sOr(v, "", "sale_price").isEmpty();
        Long price = J.minorOrNull(v, sale ? "sale_price" : "regular_price");
        if (price == null) price = J.minorOrNull(v, "price");
        return new Ext.Variant(J.s(v, "id"), J.s(v, "sku"), J.s(v, "global_unique_id"), title, price, sale ? J.minorOrNull(v, "regular_price") : null, v.path("manage_stock").asBoolean(false) ? J.intOrNull(v, "stock_quantity") : null, null);
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        ObjectNode p = HttpGateway.JSON.createObjectNode();
        p.put("name", d.title());
        p.put("description", d.description() == null ? "" : d.description());
        if (d.images() != null) { ArrayNode im = p.putArray("images"); d.images().stream().limit(10).forEach((u) -> im.addObject().put("src", u)); }
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> names = d.optionNames() == null ? List.of() : d.optionNames();
        if (d.variants().size() == 1 && names.isEmpty()) {
            Ext.VariantDraft v = d.variants().get(0);
            p.put("type", "simple");
            p.put("sku", v.sku());
            price(p, v.priceMinor(), v.compareAtMinor());
            p.put("manage_stock", true);
            p.put("stock_quantity", v.quantity());
            JsonNode created = send(c, "POST", "/products", p).json();
            ids.put(v.sku(), J.s(created, "id"));
            return new Ext.Created(J.s(created, "id"), ids);
        }
        p.put("type", "variable");
        ArrayNode attrs = p.putArray("attributes");
        for (String n : names) {
            ObjectNode a = attrs.addObject();
            a.put("name", n); a.put("visible", true); a.put("variation", true);
            ArrayNode vals = a.putArray("options");
            d.variants().stream().map((v) -> v.options().get(n)).filter((x) -> x != null).distinct().forEach(vals::add);
        }
        JsonNode created = send(c, "POST", "/products", p).json();
        String pid = J.s(created, "id");
        ArrayNode batch = HttpGateway.JSON.createArrayNode();
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode o = batch.addObject();
            o.put("sku", v.sku());
            price(o, v.priceMinor(), v.compareAtMinor());
            o.put("manage_stock", true);
            o.put("stock_quantity", v.quantity());
            ArrayNode a = o.putArray("attributes");
            for (String n : names) a.addObject().put("name", n).put("option", v.options().getOrDefault(n, ""));
        }
        ObjectNode body = HttpGateway.JSON.createObjectNode();
        body.set("create", batch);
        for (JsonNode v : J.list(send(c, "POST", "/products/" + pid + "/variations/batch", body).json().path("create"))) ids.put(J.s(v, "sku"), J.s(v, "id"));
        return new Ext.Created(pid, ids);
    }

    /** A compare-at price above the price shows as a sale: regular = compare-at, sale = price. */
    private static void price(ObjectNode n, long price, Long compareAt) {
        if (compareAt != null && compareAt > price) { n.put("regular_price", ChannelAdapter.amount(compareAt)); n.put("sale_price", ChannelAdapter.amount(price)); }
        else { n.put("regular_price", ChannelAdapter.amount(price)); n.put("sale_price", ""); }
    }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        ObjectNode n = HttpGateway.JSON.createObjectNode();
        if (priceMinor != null) price(n, priceMinor, compareAtMinor);
        if (quantity != null) { n.put("manage_stock", true); n.put("stock_quantity", Math.max(0, quantity)); }
        if (n.isEmpty()) return;
        send(c, "PUT", productId.equals(variantId) ? "/products/" + productId : "/products/" + productId + "/variations/" + variantId, n);
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        HttpGateway.Res r = get(c, "/orders?per_page=50&page=" + page + "&orderby=date&order=desc" + (since == null ? "" : "&after=" + java.net.URLEncoder.encode(since.toString().replace("Z", ""), StandardCharsets.UTF_8)));
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.json())) {
            JsonNode bill = o.path("billing"), ship = o.path("shipping");
            List<Ext.Item> items = new ArrayList<>();
            for (JsonNode li : J.list(o.path("line_items"))) {
                int q = J.intOrNull(li, "quantity") == null ? 1 : J.intOrNull(li, "quantity");
                items.add(new Ext.Item(J.s(li, "sku"), J.s(li, "name"), q, J.minor(li, "price"), J.minor(li, "total"), J.s(li, "image", "src")));
            }
            String status = J.sOr(o, "", "status");
            String stage = switch (status) { case "processing" -> "PROCESSING"; case "completed" -> "DELIVERED"; case "cancelled", "failed" -> "CANCELLED"; case "refunded" -> "RETURNED"; default -> "NEW"; };
            Map<String, Object> addr = new LinkedHashMap<>();
            addr.put("line1", J.sOr(ship, J.s(bill, "address_1"), "address_1")); addr.put("line2", J.s(ship, "address_2")); addr.put("city", J.sOr(ship, J.s(bill, "city"), "city")); addr.put("region", J.s(ship, "state")); addr.put("country", J.sOr(ship, J.s(bill, "country"), "country")); addr.put("zip", J.s(ship, "postcode"));
            out.add(new Ext.Order(J.s(o, "id"), J.sOr(o, J.s(o, "id"), "number"), status, stage, J.minor(o, "total"), J.sOr(o, "EGP", "currency"), J.join(J.s(bill, "first_name"), J.s(bill, "last_name")), J.s(bill, "phone"), J.s(bill, "email"), addr, items, J.time(o, "date_created_gmt") != null ? J.time(o, "date_created_gmt") : J.time(o, "date_created"), null, null));
        }
        int total = Integer.parseInt(r.header("X-WP-TotalPages") == null ? "1" : r.header("X-WP-TotalPages").trim());
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }

    @Override
    public List<Ext.Action> actions(Ext.Order o) {
        return switch (o.stage()) {
            case "NEW" -> List.of(new Ext.Action("processing", "Accept (processing)", false), new Ext.Action("cancel", "Cancel order", false));
            case "PROCESSING" -> List.of(new Ext.Action("complete", "Mark as completed", true), new Ext.Action("cancel", "Cancel order", false));
            default -> List.of();
        };
    }

    @Override
    public void act(Ctx c, String id, String action, Map<String, String> params) {
        String status = switch (action) { case "processing" -> "processing"; case "complete" -> "completed"; case "cancel" -> "cancelled"; default -> throw new HttpGateway.ApiException(400, "Unknown action"); };
        send(c, "PUT", "/orders/" + id, Map.of("status", status));
        String tracking = params.get("trackingNumber");
        if ("completed".equals(status) && tracking != null && !tracking.isBlank())
            send(c, "POST", "/orders/" + id + "/notes", Map.of("note", "Shipped" + (params.get("carrier") == null ? "" : " with " + params.get("carrier")) + ". Tracking: " + tracking + (params.get("trackingUrl") == null ? "" : " " + params.get("trackingUrl")), "customer_note", true));
    }
}
