package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import com.platform.integrations.Providers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Zid (Saudi Arabia) merchant API. Every call carries two tokens the OAuth exchange returns: {@code authorization} (as the bearer) and
 * {@code managerToken} (the store-level token, as X-Manager-Token). One elmanassa variant becomes one Zid product, like Salla.
 */
@Component
public class ZidAdapter implements ChannelAdapter {
    static final String BASE = "https://api.zid.sa/v1";

    @Override public String provider() { return "ZID"; }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + c.need("authorization"));
        h.put("X-Manager-Token", c.need("managerToken"));
        h.put("Accept", "application/json");
        h.put("Accept-Language", "en");
        if (c.opt("storeId") != null) { h.put("Store-Id", c.opt("storeId")); h.put("Role", "Manager"); }
        HttpGateway.Res r = c.http().send(body == null && "GET".equals(method) ? Req.get(BASE + path, h, c.rateKey()) : Req.json(method, BASE + path, h, body, c.rateKey()));
        if (r.status() == 401 || r.status() == 403) throw new HttpGateway.ApiException(401, "Zid did not accept these tokens");
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
        return r;
    }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        JsonNode j = call(c, "GET", "/managers/account/profile", null).json();
        JsonNode store = j.path("user").path("store");
        String id = J.sOr(store, J.sOr(j, "zid", "user", "id"), "id");
        return new Ext.AccountInfo(id, J.sOr(store, J.sOr(j, "Zid store", "user", "name"), "title"), J.sOr(store, "SAR", "currency"), null);
    }

    /** Names come back as plain text in the requested language, or as {ar, en}. */
    private static String name(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return "";
        return n.isObject() ? J.sOr(n, J.sOr(n, "", "ar"), "en") : n.asText("");
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        JsonNode r = call(c, "GET", "/products/?page=" + page + "&page_size=50&extended=true", null).json();
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.path("results"))) {
            List<String> images = new ArrayList<>();
            for (JsonNode i : J.list(p.path("images"))) { String u = J.sOr(i, J.s(i, "image", "full_size"), "origin"); if (u != null) images.add(u); }
            List<Ext.Variant> variants = new ArrayList<>();
            for (JsonNode v : J.list(p.path("variants"))) {
                if (J.s(v, "id") == null) continue;
                variants.add(variant(v, name(v.path("name"))));
            }
            if (variants.isEmpty()) variants.add(variant(p, null));
            out.add(new Ext.Product(J.s(p, "id"), name(p.path("name")), J.plain(name(p.path("description"))), null, images, Boolean.TRUE.equals(p.path("is_published").asBoolean(true)) ? "ACTIVE" : "DRAFT", variants));
        }
        return new Ext.Page<>(out, r.hasNonNull("next") ? String.valueOf(page + 1) : null);
    }

    private static Ext.Variant variant(JsonNode v, String title) {
        boolean sale = J.minor(v, "sale_price") > 0;
        Long price = sale ? J.minorOrNull(v, "sale_price") : J.minorOrNull(v, "price");
        Long compare = sale ? J.minorOrNull(v, "price") : null;
        Integer qty = v.path("is_infinite").asBoolean(false) ? null : J.intOrNull(v, "quantity");
        return new Ext.Variant(J.s(v, "id"), J.s(v, "sku"), J.s(v, "barcode"), title, price, compare, qty, null);
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        Map<String, String> ids = new LinkedHashMap<>();
        String first = null;
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode p = HttpGateway.JSON.createObjectNode();
            String suffix = v.options() == null || v.options().isEmpty() ? "" : " - " + String.join(" / ", v.options().values());
            ObjectNode nm = p.putObject("name");
            nm.put("ar", d.title() + suffix);
            nm.put("en", d.title() + suffix);
            p.put("sku", v.sku());
            boolean sale = v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor();
            p.put("price", Double.parseDouble(ChannelAdapter.amount(sale ? v.compareAtMinor() : v.priceMinor())));
            if (sale) p.put("sale_price", Double.parseDouble(ChannelAdapter.amount(v.priceMinor())));
            p.put("quantity", Math.max(0, v.quantity()));
            String id = J.s(call(c, "POST", "/products/", p).json(), "id");
            if (id == null) throw new HttpGateway.ApiException(422, "Zid did not return the new product");
            ids.put(v.sku(), id);
            if (first == null) first = id;
        }
        return new Ext.Created(first, ids);
    }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        ObjectNode n = HttpGateway.JSON.createObjectNode();
        if (priceMinor != null) {
            boolean sale = compareAtMinor != null && compareAtMinor > priceMinor;
            n.put("price", Double.parseDouble(ChannelAdapter.amount(sale ? compareAtMinor : priceMinor)));
            if (sale) n.put("sale_price", Double.parseDouble(ChannelAdapter.amount(priceMinor)));
            else n.putNull("sale_price");
        }
        if (quantity != null) n.put("quantity", Math.max(0, quantity));
        if (!n.isEmpty()) call(c, "PATCH", "/products/" + productId + "/", n);
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        String from = since == null ? "" : "&date_from=" + since.atOffset(java.time.ZoneOffset.UTC).toLocalDate() + "&date_attribute=created_at";
        JsonNode r = call(c, "GET", "/managers/store/orders?payload_type=default&per_page=50&page=" + page + from, null).json();
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.path("orders"))) out.add(order(o, c));
        int shown = page * 50;
        return new Ext.Page<>(out, shown < r.path("total_order_count").asInt(0) && !out.isEmpty() ? String.valueOf(page + 1) : null);
    }

    static Ext.Order order(JsonNode o, Ctx c) {
        String st = J.sOr(o, "new", "order_status", "code");
        if ("new".equals(st) && J.s(o, "order_status") == null) st = J.sOr(o, "new", "order_status");
        String stage = switch (st) {
            case "preparing", "ready" -> "PROCESSING"; case "indelivery" -> "SHIPPED"; case "delivered" -> "DELIVERED"; case "canceled", "cancelled" -> "CANCELLED";
            case "reversed", "reverse_in_progress", "partially_reversed" -> "RETURNED"; default -> "NEW";
        };
        JsonNode cust = o.path("customer");
        List<Ext.Item> items = new ArrayList<>();
        for (JsonNode it : J.list(o.path("products"))) {
            int q = J.intOrNull(it, "quantity") == null ? 1 : J.intOrNull(it, "quantity");
            long unit = J.minor(it, "price");
            long total = J.minor(it, "total");
            JsonNode img = it.path("images").isArray() && it.path("images").size() > 0 ? it.path("images").get(0) : null;
            items.add(new Ext.Item(J.s(it, "sku"), name(it.path("name")), q, unit, total > 0 ? total : unit * q, img == null ? null : J.sOr(img, J.s(img, "origin"), "thumbs", "thumbnail")));
        }
        JsonNode addr = o.path("shipping").path("address");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("line1", J.sOr(addr, J.s(addr, "street"), "formatted_address")); a.put("city", J.sOr(addr, J.s(addr, "city", "name"), "meta", "city_name"));
        a.put("country", J.s(addr, "country", "name")); a.put("zip", J.s(addr, "meta", "postcode"));
        String tracking = J.s(o, "shipping", "method", "tracking_number");
        return new Ext.Order(J.s(o, "id"), J.sOr(o, J.s(o, "id"), "code"), st, stage, J.minor(o, "order_total"), J.sOr(o, J.sOr(o, Providers.currencyOf(c.country()), "currency_code"), "currency", "code"),
                J.s(cust, "name"), J.s(cust, "mobile"), J.s(cust, "email"), a, items, J.time(o, "created_at"), tracking, J.s(o, "shipping", "method", "name"));
    }

    @Override
    public List<Ext.Action> actions(Ext.Order o) {
        return switch (o.stage()) {
            case "NEW" -> List.of(new Ext.Action("preparing", "Start preparing", false), new Ext.Action("cancelled", "Cancel order", false));
            case "PROCESSING" -> List.of(new Ext.Action("indelivery", "Mark as shipped", true), new Ext.Action("cancelled", "Cancel order", false));
            case "SHIPPED" -> List.of(new Ext.Action("delivered", "Mark as delivered", false));
            default -> List.of();
        };
    }

    @Override
    public void act(Ctx c, String id, String action, Map<String, String> params) {
        if (!List.of("preparing", "indelivery", "delivered", "cancelled").contains(action)) throw new HttpGateway.ApiException(400, "Unknown action");
        ObjectNode b = HttpGateway.JSON.createObjectNode();
        b.put("order_status", action);
        if (params != null && params.get("trackingNumber") != null && !params.get("trackingNumber").isBlank()) b.put("tracking_number", params.get("trackingNumber"));
        call(c, "POST", "/managers/store/orders/" + id + "/change-order-status", b);
    }
}