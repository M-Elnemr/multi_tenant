package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Shopify Admin REST API (a custom app's Admin API access token per store). */
@Component
public class ShopifyAdapter implements ChannelAdapter {
    static final String VERSION = "2024-10";
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    @Override public String provider() { return "SHOPIFY"; }

    /** Shopify's admin API always answers on {shop}.myshopify.com, so that is the only host we accept (a bare shop name is completed). */
    private static String host(Ctx c) {
        String d = c.need("shopDomain").toLowerCase().replaceFirst("^https?://", "").replaceAll("[/?#].*$", "");
        if (!d.contains(".")) d += ".myshopify.com";
        if (!d.matches("[a-z0-9][a-z0-9-]*\\.myshopify\\.com")) throw new HttpGateway.ApiException(400, "Use your store address, like my-store.myshopify.com");
        return d;
    }

    private static String base(Ctx c) { return "https://" + host(c) + "/admin/api/" + VERSION; }

    private static Map<String, String> headers(Ctx c) { return Map.of("X-Shopify-Access-Token", c.need("accessToken"), "Accept", "application/json"); }

    private HttpGateway.Res get(Ctx c, String pathOrUrl) {
        return c.http().call(Req.get(pathOrUrl.startsWith("http") ? pathOrUrl : base(c) + pathOrUrl, headers(c), c.rateKey()));
    }

    private HttpGateway.Res send(Ctx c, String method, String path, Object body) {
        return c.http().call(Req.json(method, base(c) + path, headers(c), body, c.rateKey()));
    }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        JsonNode shop = get(c, "/shop.json").json().path("shop");
        if (shop.isMissingNode()) throw new HttpGateway.ApiException(401, "Shopify did not accept this token");
        return new Ext.AccountInfo(J.s(shop, "id"), J.sOr(shop, host(c), "name"), J.sOr(shop, "EGP", "currency"), null);
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        HttpGateway.Res r = get(c, cursor == null ? "/products.json?limit=100" : "/products.json?limit=100&page_info=" + cursor);
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.json().path("products"))) {
            List<Ext.Variant> vs = new ArrayList<>();
            for (JsonNode v : J.list(p.path("variants")))
                vs.add(new Ext.Variant(J.s(v, "id"), J.s(v, "sku"), J.s(v, "barcode"), "Default Title".equals(J.s(v, "title")) ? null : J.s(v, "title"), J.minorOrNull(v, "price"), J.minorOrNull(v, "compare_at_price"),
                        J.intOrNull(v, "inventory_quantity"), J.s(v, "inventory_item_id")));
            List<String> images = new ArrayList<>();
            for (JsonNode i : J.list(p.path("images"))) { String src = J.s(i, "src"); if (src != null) images.add(src); }
            out.add(new Ext.Product(J.s(p, "id"), J.s(p, "title"), J.plain(J.s(p, "body_html")), J.s(p, "vendor"), images, J.s(p, "status"), vs));
        }
        String link = r.header("Link");
        String next = null;
        if (link != null) { Matcher m = NEXT.matcher(link); if (m.find()) { Matcher pi = Pattern.compile("page_info=([^&>]+)").matcher(m.group(1)); if (pi.find()) next = pi.group(1); } }
        return new Ext.Page<>(out, next);
    }

    private String firstLocation(Ctx c) {
        List<JsonNode> locs = J.list(get(c, "/locations.json").json().path("locations"));
        for (JsonNode l : locs) if (l.path("active").asBoolean(true)) return J.s(l, "id");
        throw new HttpGateway.ApiException(400, "This Shopify store has no stock location");
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        ObjectNode product = HttpGateway.JSON.createObjectNode();
        product.put("title", d.title());
        product.put("body_html", J.html(d.description()));
        if (d.brand() != null) product.put("vendor", d.brand());
        product.put("status", "active");
        List<String> names = d.optionNames() == null ? List.of() : d.optionNames();
        ArrayNode opts = product.putArray("options");
        for (String n : names.stream().limit(3).toList()) opts.addObject().put("name", n);
        if (names.isEmpty()) product.remove("options");
        ArrayNode variants = product.putArray("variants");
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode o = variants.addObject();
            o.put("sku", v.sku());
            if (v.barcode() != null && !v.barcode().isBlank()) o.put("barcode", v.barcode());
            o.put("price", ChannelAdapter.amount(v.priceMinor()));
            if (v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor()) o.put("compare_at_price", ChannelAdapter.amount(v.compareAtMinor()));
            o.put("inventory_management", "shopify");
            for (int i = 0; i < Math.min(3, names.size()); i++) o.put("option" + (i + 1), v.options().getOrDefault(names.get(i), "-"));
        }
        if (d.images() != null && !d.images().isEmpty()) { ArrayNode im = product.putArray("images"); d.images().stream().limit(10).forEach((u) -> im.addObject().put("src", u)); }
        JsonNode created = send(c, "POST", "/products.json", Map.of("product", product)).json().path("product");
        Map<String, String> ids = new LinkedHashMap<>();
        String loc = null;
        for (JsonNode v : J.list(created.path("variants"))) {
            String sku = J.s(v, "sku");
            ids.put(sku, J.s(v, "id"));
            Ext.VariantDraft draft = d.variants().stream().filter((x) -> x.sku().equals(sku)).findFirst().orElse(null);
            if (draft == null) continue;
            if (loc == null) loc = firstLocation(c);
            send(c, "POST", "/inventory_levels/set.json", Map.of("location_id", Long.parseLong(loc), "inventory_item_id", Long.parseLong(J.s(v, "inventory_item_id")), "available", draft.quantity()));
        }
        return new Ext.Created(J.s(created, "id"), ids);
    }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        if (priceMinor != null || compareAtMinor != null) {
            ObjectNode v = HttpGateway.JSON.createObjectNode();
            v.put("id", Long.parseLong(variantId));
            if (priceMinor != null) v.put("price", ChannelAdapter.amount(priceMinor));
            if (compareAtMinor != null) v.put("compare_at_price", compareAtMinor > 0 && (priceMinor == null || compareAtMinor > priceMinor) ? ChannelAdapter.amount(compareAtMinor) : null);
            send(c, "PUT", "/variants/" + variantId + ".json", Map.of("variant", v));
        }
        if (quantity != null) {
            String item = inventoryRef != null && !inventoryRef.isBlank() ? inventoryRef : J.s(get(c, "/variants/" + variantId + ".json").json(), "variant", "inventory_item_id");
            send(c, "POST", "/inventory_levels/set.json", Map.of("location_id", Long.parseLong(firstLocation(c)), "inventory_item_id", Long.parseLong(item), "available", Math.max(0, quantity)));
        }
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        String path = cursor == null ? "/orders.json?status=any&limit=100" + (since == null ? "" : "&created_at_min=" + java.net.URLEncoder.encode(since.toString(), java.nio.charset.StandardCharsets.UTF_8))
                : "/orders.json?limit=100&page_info=" + cursor;
        HttpGateway.Res r = get(c, path);
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.json().path("orders"))) out.add(order(c, o));
        String link = r.header("Link");
        String next = null;
        if (link != null) { Matcher m = NEXT.matcher(link); if (m.find()) { Matcher pi = Pattern.compile("page_info=([^&>]+)").matcher(m.group(1)); if (pi.find()) next = pi.group(1); } }
        return new Ext.Page<>(out, next);
    }

    Ext.Order order(Ctx c, JsonNode o) {
        JsonNode ship = o.path("shipping_address");
        String name = J.sOr(ship, J.join(J.s(o, "customer", "first_name"), J.s(o, "customer", "last_name")), "name");
        List<Ext.Item> items = new ArrayList<>();
        for (JsonNode li : J.list(o.path("line_items"))) {
            int q = J.intOrNull(li, "quantity") == null ? 1 : J.intOrNull(li, "quantity");
            long unit = J.minor(li, "price");
            items.add(new Ext.Item(J.s(li, "sku"), J.s(li, "title"), q, unit, unit * q, null));
        }
        JsonNode f = o.path("fulfillments").isArray() && o.path("fulfillments").size() > 0 ? o.path("fulfillments").get(0) : null;
        String shipStatus = f == null ? null : J.s(f, "shipment_status");
        String stage;
        if (o.hasNonNull("cancelled_at")) stage = "CANCELLED";
        else if ("refunded".equals(J.s(o, "financial_status"))) stage = "RETURNED";
        else if ("delivered".equals(shipStatus)) stage = "DELIVERED";
        else if ("fulfilled".equals(J.s(o, "fulfillment_status"))) stage = "SHIPPED";
        else if ("partial".equals(J.s(o, "fulfillment_status"))) stage = "PROCESSING";
        else stage = "NEW";
        Map<String, Object> addr = new LinkedHashMap<>();
        addr.put("line1", J.s(ship, "address1")); addr.put("line2", J.s(ship, "address2")); addr.put("city", J.s(ship, "city")); addr.put("region", J.s(ship, "province")); addr.put("country", J.s(ship, "country")); addr.put("zip", J.s(ship, "zip"));
        return new Ext.Order(J.s(o, "id"), J.sOr(o, J.s(o, "id"), "name"), J.sOr(o, J.sOr(o, "", "financial_status"), "fulfillment_status"), stage, J.minor(o, "total_price"), J.sOr(o, "EGP", "currency"),
                name, J.sOr(ship, J.sOr(o, J.s(o, "customer", "phone"), "phone"), "phone"), J.sOr(o, J.s(o, "customer", "email"), "email"), addr, items, J.time(o, "created_at"),
                f == null ? null : J.s(f, "tracking_number"), f == null ? null : J.s(f, "tracking_company"));
    }

    @Override
    public List<Ext.Action> actions(Ext.Order o) {
        return switch (o.stage()) {
            case "NEW", "PROCESSING" -> List.of(new Ext.Action("fulfill", "Mark as shipped", true), new Ext.Action("cancel", "Cancel order", false));
            default -> List.of();
        };
    }

    @Override
    public void act(Ctx c, String id, String action, Map<String, String> params) {
        if ("cancel".equals(action)) { send(c, "POST", "/orders/" + id + "/cancel.json", Map.of()); return; }
        if (!"fulfill".equals(action)) throw new HttpGateway.ApiException(400, "Unknown action");
        List<JsonNode> fos = J.list(get(c, "/orders/" + id + "/fulfillment_orders.json").json().path("fulfillment_orders"));
        ArrayNode lines = HttpGateway.JSON.createArrayNode();
        for (JsonNode fo : fos) if (List.of("open", "in_progress").contains(J.s(fo, "status"))) lines.addObject().put("fulfillment_order_id", Long.parseLong(J.s(fo, "id")));
        if (lines.isEmpty()) throw new HttpGateway.ApiException(409, "Nothing left to ship on this order");
        ObjectNode f = HttpGateway.JSON.createObjectNode();
        f.set("line_items_by_fulfillment_order", lines);
        f.put("notify_customer", true);
        String tracking = params.get("trackingNumber");
        if (tracking != null && !tracking.isBlank()) {
            ObjectNode t = f.putObject("tracking_info");
            t.put("number", tracking);
            if (params.get("carrier") != null) t.put("company", params.get("carrier"));
            if (params.get("trackingUrl") != null && !params.get("trackingUrl").isBlank()) t.put("url", params.get("trackingUrl"));
        }
        send(c, "POST", "/fulfillments.json", Map.of("fulfillment", f));
    }
}
