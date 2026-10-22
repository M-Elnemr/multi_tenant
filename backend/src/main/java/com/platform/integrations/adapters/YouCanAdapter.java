package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import com.platform.integrations.OAuthApps;
import com.platform.integrations.Providers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * YouCan Store Admin API (OAuth 2 bearer). BETA: written from YouCan's published OAuth and resource names; field names are read defensively and the
 * first live store may need small adjustments. One elmanassa variant becomes one YouCan product.
 */
@Component
public class YouCanAdapter implements ChannelAdapter {
    static final String BASE = "https://api.youcan.shop";
    private final OAuthApps apps;

    public YouCanAdapter(OAuthApps apps) { this.apps = apps; }

    @Override public String provider() { return "YOUCAN"; }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body) {
        for (int attempt = 0; ; attempt++) {
            Map<String, String> h = Map.of("Authorization", "Bearer " + c.need("accessToken"), "Accept", "application/json");
            HttpGateway.Res r = c.http().send("GET".equals(method) ? Req.get(BASE + path, h, c.rateKey()) : Req.json(method, BASE + path, h, body, c.rateKey()));
            if (r.status() == 401 && attempt == 0 && refresh(c)) continue;
            if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
            return r;
        }
    }

    private boolean refresh(Ctx c) {
        OAuthApps.App app = apps.of("YOUCAN");
        String rt = c.opt("refreshToken");
        if (rt == null || !app.ok()) return false;
        HttpGateway.Res r = c.http().send(Req.json("POST", BASE + "/oauth/token", Map.of("Accept", "application/json"), Map.of("grant_type", "refresh_token", "refresh_token", rt, "client_id", app.clientId(), "client_secret", app.clientSecret()), "youcan-oauth"));
        if (!r.ok() || r.json().path("access_token").asText("").isEmpty()) return false;
        Map<String, String> updated = new LinkedHashMap<>(c.cred());
        updated.put("accessToken", r.json().path("access_token").asText());
        if (r.json().hasNonNull("refresh_token")) updated.put("refreshToken", r.json().path("refresh_token").asText());
        c.cred().putAll(updated);
        c.saveCredentials().accept(updated);
        return true;
    }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        JsonNode r = call(c, "GET", "/products?limit=1", null).json();   // a cheap authenticated read: 401 means the token is not valid
        JsonNode me = null;
        try { me = call(c, "GET", "/me", null).json(); } catch (HttpGateway.ApiException ignored) { /* not all tokens may read the profile */ }
        String store = me == null ? null : J.sOr(me, J.s(me, "data", "store", "name"), "store", "name");
        return new Ext.AccountInfo(J.sOr(me == null ? r : me, "youcan", "data", "store", "id"), store == null ? "YouCan store" : store, Providers.currencyOf(c.country()), null);
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        JsonNode r = call(c, "GET", "/products?limit=50&page=" + page, null).json();
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.path("data"))) {
            List<String> images = new ArrayList<>();
            for (JsonNode i : J.list(p.path("images"))) { String u = J.sOr(i, J.s(i, "image_url"), "url"); if (u != null) images.add(u); }
            List<Ext.Variant> vs = new ArrayList<>();
            for (JsonNode v : J.list(p.path("variants")))
                vs.add(new Ext.Variant(J.s(v, "id"), J.s(v, "sku"), null, variantTitle(v), J.minorOrNull(v, "price"), J.minorOrNull(v, "compare_at_price"), J.intOrNull(v, "inventory"), null));
            if (vs.isEmpty()) vs.add(new Ext.Variant(J.s(p, "id"), J.s(p, "sku"), null, null, J.minorOrNull(p, "price"), J.minorOrNull(p, "compare_at_price"), J.intOrNull(p, "inventory"), null));
            out.add(new Ext.Product(J.s(p, "id"), J.s(p, "name"), J.plain(J.s(p, "description")), null, images, J.sOr(p, "active", "visibility"), vs));
        }
        int total = r.path("meta").path("pagination").path("total_pages").asInt(1);
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }

    private static String variantTitle(JsonNode v) {
        List<String> parts = new ArrayList<>();
        JsonNode vars = v.path("variations");
        if (vars.isObject()) vars.fields().forEachRemaining((e) -> parts.add(e.getValue().asText()));
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        Map<String, String> ids = new LinkedHashMap<>();
        String first = null;
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode p = HttpGateway.JSON.createObjectNode();
            String suffix = v.options() == null || v.options().isEmpty() ? "" : " - " + String.join(" / ", v.options().values());
            p.put("name", d.title() + suffix);
            p.put("description", d.description() == null ? "" : d.description());
            p.put("sku", v.sku());
            p.put("price", Double.parseDouble(ChannelAdapter.amount(v.priceMinor())));
            if (v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor()) p.put("compare_at_price", Double.parseDouble(ChannelAdapter.amount(v.compareAtMinor())));
            p.put("inventory", v.quantity());
            p.put("track_inventory", true);
            if (d.images() != null) { var im = p.putArray("images"); d.images().stream().limit(8).forEach(im::add); }
            JsonNode created = call(c, "POST", "/products", p).json();
            String id = J.sOr(created, J.s(created, "data", "id"), "id");
            ids.put(v.sku(), id);
            if (first == null) first = id;
        }
        return new Ext.Created(first, ids);
    }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        ObjectNode n = HttpGateway.JSON.createObjectNode();
        if (priceMinor != null) {
            n.put("price", Double.parseDouble(ChannelAdapter.amount(priceMinor)));
            if (compareAtMinor != null) n.put("compare_at_price", compareAtMinor > priceMinor ? Double.parseDouble(ChannelAdapter.amount(compareAtMinor)) : 0);
        }
        if (quantity != null) n.put("inventory", Math.max(0, quantity));
        if (n.isEmpty()) return;
        call(c, "PUT", productId.equals(variantId) || variantId == null || variantId.isBlank() ? "/products/" + productId : "/products/" + productId + "/variants/" + variantId, n);
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        JsonNode r = call(c, "GET", "/orders?limit=50&page=" + page, null).json();
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.path("data"))) {
            JsonNode cust = o.path("customer");
            JsonNode ship = o.path("shipping").path("address").isMissingNode() ? o.path("shipping") : o.path("shipping").path("address");
            List<Ext.Item> items = new ArrayList<>();
            for (JsonNode li : J.list(o.path("variants"))) {
                int q = J.intOrNull(li, "quantity") == null ? 1 : J.intOrNull(li, "quantity");
                long unit = J.minor(li, "price");
                items.add(new Ext.Item(J.s(li, "sku"), J.sOr(li, J.s(li, "variant", "product", "name"), "product", "name"), q, unit, unit * q, null));
            }
            String status = J.sOr(o, "", "status_text");
            String raw = J.sOr(o, status, "status");
            String stage = switch (raw.toLowerCase()) { case "new", "0", "pending" -> "NEW"; case "processing", "1", "in_progress" -> "PROCESSING"; case "shipped", "delivering", "out_for_delivery" -> "SHIPPED"; case "delivered", "completed" -> "DELIVERED"; case "cancelled", "canceled" -> "CANCELLED"; case "returned", "refunded" -> "RETURNED"; default -> "NEW"; };
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("line1", J.sOr(ship, J.s(ship, "address"), "first_line")); a.put("city", J.s(ship, "city")); a.put("region", J.s(ship, "region")); a.put("country", J.s(ship, "country_code"));
            Instant placed = J.time(o, "created_at");
            if (since != null && placed != null && placed.isBefore(since)) continue;
            out.add(new Ext.Order(J.s(o, "id"), J.sOr(o, J.s(o, "id"), "ref"), raw, stage, J.minor(o, "total"), J.sOr(o, Providers.currencyOf(c.country()), "currency"), J.join(J.s(cust, "first_name"), J.s(cust, "last_name")), J.s(cust, "phone"), J.s(cust, "email"), a, items, placed, null, null));
        }
        int total = r.path("meta").path("pagination").path("total_pages").asInt(1);
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }
}
