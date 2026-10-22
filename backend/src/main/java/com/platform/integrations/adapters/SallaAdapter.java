package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import com.platform.integrations.OAuthApps;
import com.platform.integrations.Providers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Salla Merchant API (OAuth 2.0 bearer; the access token lasts ~14 days and is renewed with the one-time refresh token). One elmanassa variant becomes one Salla product. */
@Component
public class SallaAdapter implements ChannelAdapter {
    static final String BASE = "https://api.salla.dev/admin/v2";
    static final String TOKEN_URL = "https://accounts.salla.sa/oauth2/token";
    private final OAuthApps apps;

    public SallaAdapter(OAuthApps apps) { this.apps = apps; }

    @Override public String provider() { return "SALLA"; }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body) {
        for (int attempt = 0; ; attempt++) {
            Map<String, String> h = Map.of("Authorization", "Bearer " + c.need("accessToken"), "Accept", "application/json");
            HttpGateway.Res r = c.http().send(body == null && "GET".equals(method) ? Req.get(BASE + path, h, c.rateKey()) : Req.json(method, BASE + path, h, body, c.rateKey()));
            if (r.status() == 401 && attempt == 0 && refresh(c)) continue;
            if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
            return r;
        }
    }

    /** Salla refresh tokens are single-use: the new pair is saved straight away or the account would be locked out. */
    private boolean refresh(Ctx c) {
        OAuthApps.App app = apps.of("SALLA");
        String rt = c.opt("refreshToken");
        if (rt == null || !app.ok()) return false;
        HttpGateway.Res r = c.http().send(Req.form(TOKEN_URL, Map.of("Accept", "application/json"), Map.of("grant_type", "refresh_token", "refresh_token", rt, "client_id", app.clientId(), "client_secret", app.clientSecret()), "salla-oauth"));
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
        JsonNode d = call(c, "GET", "/store/info", null).json().path("data");
        if (d.isMissingNode()) throw new HttpGateway.ApiException(401, "Salla did not accept this token");
        return new Ext.AccountInfo(J.sOr(d, J.s(d, "domain"), "id"), J.sOr(d, "Salla store", "name"), J.sOr(d, Providers.currencyOf(c.country()), "currency"), null);
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        JsonNode r = call(c, "GET", "/products?per_page=50&page=" + page, null).json();
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode p : J.list(r.path("data"))) {
            List<String> images = new ArrayList<>();
            for (JsonNode i : J.list(p.path("images"))) { String u = J.sOr(i, J.s(i, "thumbnail"), "url"); if (u != null) images.add(u); }
            if (images.isEmpty() && J.s(p, "main_image") != null) images.add(J.s(p, "main_image"));
            boolean sale = J.minor(p, "sale_price", "amount") > 0;
            Long price = sale ? J.minorOrNull(p, "sale_price", "amount") : J.minorOrNull(p, "price", "amount");
            Long compare = sale ? J.minorOrNull(p, "regular_price", "amount") == null ? J.minorOrNull(p, "price", "amount") : J.minorOrNull(p, "regular_price", "amount") : null;
            out.add(new Ext.Product(J.s(p, "id"), J.s(p, "name"), J.plain(J.s(p, "description")), J.s(p, "brand", "name"), images, J.s(p, "status"),
                    List.of(new Ext.Variant(J.s(p, "id"), J.s(p, "sku"), J.s(p, "mpn"), null, price, compare, J.intOrNull(p, "quantity"), null))));
        }
        int total = r.path("pagination").path("totalPages").asInt(1);
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        Map<String, String> ids = new LinkedHashMap<>();
        String first = null;
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode p = HttpGateway.JSON.createObjectNode();
            String suffix = v.options() == null || v.options().isEmpty() ? "" : " - " + String.join(" / ", v.options().values());
            p.put("name", d.title() + suffix);
            p.put("product_type", "product");
            p.put("sku", v.sku());
            p.put("price", ChannelAdapter.amount(v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor() ? v.compareAtMinor() : v.priceMinor()).replaceFirst("\\.00$", ""));
            if (v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor()) p.put("sale_price", ChannelAdapter.amount(v.priceMinor()).replaceFirst("\\.00$", ""));
            p.put("quantity", v.quantity());
            p.put("description", d.description() == null ? "" : d.description());
            p.put("status", "sale");
            if (d.images() != null && !d.images().isEmpty()) {
                ArrayNode im = p.putArray("images");
                int n = 0;
                for (String u : d.images().stream().limit(8).toList()) { ObjectNode o = im.addObject(); o.put("original", u); o.put("thumbnail", u); o.put("alt", d.title()); o.put("default", n == 0); o.put("sort", ++n); }
            }
            String id = J.s(call(c, "POST", "/products", p).json(), "data", "id");
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
            n.put("sale_price", sale ? Double.parseDouble(ChannelAdapter.amount(priceMinor)) : 0);
        }
        if (quantity != null) n.put("quantity", Math.max(0, quantity));
        if (!n.isEmpty()) call(c, "PUT", "/products/" + productId, n);
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        int page = cursor == null ? 1 : Integer.parseInt(cursor);
        String from = since == null ? "" : "&from_date=" + since.atOffset(ZoneOffset.UTC).toLocalDate();
        JsonNode r = call(c, "GET", "/orders?per_page=30&page=" + page + from, null).json();
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.path("data"))) {
            JsonNode d;
            try { d = call(c, "GET", "/orders/" + J.s(o, "id"), null).json().path("data"); } catch (HttpGateway.ApiException e) { d = o; }   // the list answer has no items: read the order itself
            out.add(order(o, d, c));
        }
        int total = r.path("pagination").path("totalPages").asInt(1);
        return new Ext.Page<>(out, page < total ? String.valueOf(page + 1) : null);
    }

    static Ext.Order order(JsonNode listed, JsonNode d, Ctx c) {
        String slug = J.sOr(d, J.sOr(listed, "", "status", "slug"), "status", "slug");
        String stage = switch (slug) {
            case "in_progress" -> "PROCESSING"; case "delivering", "shipped" -> "SHIPPED"; case "delivered", "completed" -> "DELIVERED"; case "canceled" -> "CANCELLED"; case "restored", "restoring" -> "RETURNED"; default -> "NEW";
        };
        JsonNode cust = d.path("customer").isMissingNode() ? listed.path("customer") : d.path("customer");
        List<Ext.Item> items = new ArrayList<>();
        for (JsonNode it : J.list(d.path("items"))) {
            int q = J.intOrNull(it, "quantity") == null ? 1 : J.intOrNull(it, "quantity");
            long unit = J.minor(it, "amounts", "price_without_tax", "amount");
            long total = J.minor(it, "amounts", "total", "amount");
            items.add(new Ext.Item(J.s(it, "sku"), J.s(it, "name"), q, unit, total > 0 ? total : unit * q, J.s(it, "thumbnail")));
        }
        JsonNode addr = d.path("shipping").path("address").isMissingNode() ? d.path("ship_to") : d.path("shipping").path("address");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("line1", J.sOr(addr, J.s(addr, "street"), "shipping_address")); a.put("city", J.s(addr, "city")); a.put("country", J.s(addr, "country")); a.put("zip", J.s(addr, "postal_code"));
        String phone = J.s(cust, "mobile_code") == null ? J.s(cust, "mobile") : "+" + J.s(cust, "mobile_code").replace("+", "") + J.s(cust, "mobile");
        String tracking = J.s(d, "shipments", "0", "tracking_number");
        return new Ext.Order(J.s(listed, "id"), J.sOr(listed, J.s(listed, "id"), "reference_id"), J.sOr(d, slug, "status", "name"), stage, J.minor(listed, "amounts", "total", "amount"), J.sOr(listed, Providers.currencyOf(c.country()), "amounts", "total", "currency"),
                J.join(J.s(cust, "first_name"), J.s(cust, "last_name")), phone, J.s(cust, "email"), a, items, J.time(listed, "date", "date"), tracking, J.s(d, "shipments", "0", "courier_name"));
    }

    @Override
    public List<Ext.Action> actions(Ext.Order o) {
        return switch (o.stage()) {
            case "NEW" -> List.of(new Ext.Action("in_progress", "Accept (in progress)", false), new Ext.Action("canceled", "Cancel order", false));
            case "PROCESSING" -> List.of(new Ext.Action("delivering", "Mark as shipped", false), new Ext.Action("canceled", "Cancel order", false));
            case "SHIPPED" -> List.of(new Ext.Action("delivered", "Mark as delivered", false));
            default -> List.of();
        };
    }

    @Override
    public void act(Ctx c, String id, String action, Map<String, String> params) {
        if (!List.of("in_progress", "delivering", "delivered", "canceled").contains(action)) throw new HttpGateway.ApiException(400, "Unknown action");
        call(c, "POST", "/orders/" + id + "/status", Map.of("slug", action));
    }
}
