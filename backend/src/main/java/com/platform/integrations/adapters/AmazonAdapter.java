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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Amazon Selling Partner API for amazon.eg / amazon.sa / amazon.ae (the EMEA endpoint). Auth is Login with Amazon: the seller's refresh token is exchanged for a
 * one-hour access token (no AWS signing is needed any more). The app keys are the seller's own (private app) or elmanassa's (the "log in" flow).
 */
@Component
public class AmazonAdapter implements ChannelAdapter {
    static final String HOST = "https://sellingpartnerapi-eu.amazon.com";
    static final String LWA = "https://api.amazon.com/auth/o2/token";
    static final Map<String, String> MARKETPLACE = Map.of("EG", "ARBP9OOSHTCHU", "SA", "A17E79C6D8DWNP", "AE", "A2VIGQ35RCS4UG");
    private record Token(String value, long expires) {}
    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    private final OAuthApps apps;

    public AmazonAdapter(OAuthApps apps) { this.apps = apps; }

    @Override public String provider() { return "AMAZON"; }

    static String marketplace(Ctx c) {
        String m = MARKETPLACE.get(c.country());
        if (m == null) throw new HttpGateway.ApiException(400, "Amazon is available for Egypt, Saudi Arabia and the UAE");
        return m;
    }

    /** Exchanges an authorization code (from the seller's consent) for the refresh token that is then stored. */
    public static Map<String, String> exchangeCode(HttpGateway http, String clientId, String clientSecret, String code) {
        HttpGateway.Res r = http.call(Req.form(LWA, Map.of("Accept", "application/json"), Map.of("grant_type", "authorization_code", "code", code, "client_id", clientId, "client_secret", clientSecret), "amazon-lwa"));
        return Map.of("refreshToken", r.json().path("refresh_token").asText());
    }

    private String accessToken(Ctx c) {
        String rt = c.need("refreshToken");
        Token t = TOKENS.get(rt);
        if (t != null && t.expires() > System.currentTimeMillis() + 60_000) return t.value();
        OAuthApps.App app = apps.of("AMAZON");
        String id = c.opt("lwaClientId") != null ? c.opt("lwaClientId") : app.clientId();
        String secret = c.opt("lwaClientSecret") != null ? c.opt("lwaClientSecret") : app.clientSecret();
        if (id == null || secret == null) throw new HttpGateway.ApiException(400, "Amazon app keys are missing (LWA client id and secret)");
        HttpGateway.Res r = c.http().send(Req.form(LWA, Map.of("Accept", "application/json"), Map.of("grant_type", "refresh_token", "refresh_token", rt, "client_id", id, "client_secret", secret), "amazon-lwa"));
        if (!r.ok()) throw new HttpGateway.ApiException(401, "Amazon did not accept these credentials (" + HttpGateway.describe(r) + ")");
        String value = r.json().path("access_token").asText();
        TOKENS.put(rt, new Token(value, System.currentTimeMillis() + r.json().path("expires_in").asLong(3600) * 1000));
        return value;
    }

    private HttpGateway.Res call(Ctx c, String method, String path, Object body, String rateKey) {
        Map<String, String> h = Map.of("x-amz-access-token", accessToken(c), "Accept", "application/json");
        HttpGateway.Res r = c.http().send("GET".equals(method) ? Req.get(HOST + path, h, rateKey) : Req.json(method, HOST + path, h, body, rateKey));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status(), describeAmazon(r));
        return r;
    }

    private static String describeAmazon(HttpGateway.Res r) {
        JsonNode e = r.json().path("errors");
        if (e.isArray() && e.size() > 0) return "HTTP " + r.status() + ": " + J.sOr(e.get(0), J.sOr(e.get(0), "Amazon refused the request", "code"), "message");
        return HttpGateway.describe(r);
    }

    private String sellerId(Ctx c) { return c.need("sellerId"); }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        String mp = marketplace(c);
        JsonNode payload = call(c, "GET", "/sellers/v1/marketplaceParticipations", null, "amazon-sellers|gap=2200").json().path("payload");
        for (JsonNode p : J.list(payload)) {
            if (mp.equals(J.s(p, "marketplace", "id"))) {
                if (p.path("participation").path("isSuspendedParticipation").asBoolean(false)) throw new HttpGateway.ApiException(403, "Your Amazon seller account is suspended in this marketplace");
                return new Ext.AccountInfo(sellerId(c), "Amazon " + J.sOr(p, c.country(), "marketplace", "name"), J.sOr(p, Providers.currencyOf(c.country()), "marketplace", "defaultCurrencyCode"), null);
            }
        }
        throw new HttpGateway.ApiException(403, "This seller account does not sell on the Amazon marketplace of " + c.country());
    }

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) {
        String mp = marketplace(c);
        String path = "/listings/2021-08-01/items/" + enc(sellerId(c)) + "?marketplaceIds=" + mp + "&includedData=summaries,offers,fulfillmentAvailability&pageSize=20" + (cursor == null ? "" : "&pageToken=" + enc(cursor));
        JsonNode r = call(c, "GET", path, null, "amazon-listings|gap=600").json();
        List<Ext.Product> out = new ArrayList<>();
        for (JsonNode i : J.list(r.path("items"))) {
            JsonNode sum = i.path("summaries").isArray() && i.path("summaries").size() > 0 ? i.path("summaries").get(0) : null;
            JsonNode offer = i.path("offers").isArray() && i.path("offers").size() > 0 ? i.path("offers").get(0) : null;
            JsonNode fa = i.path("fulfillmentAvailability").isArray() && i.path("fulfillmentAvailability").size() > 0 ? i.path("fulfillmentAvailability").get(0) : null;
            String sku = J.s(i, "sku");
            List<String> images = new ArrayList<>();
            if (sum != null && J.s(sum, "mainImage", "link") != null) images.add(J.s(sum, "mainImage", "link"));
            out.add(new Ext.Product(J.sOr(sum == null ? i : sum, sku, "asin"), sum == null ? sku : J.sOr(sum, sku, "itemName"), "", sum == null ? null : J.s(sum, "brand"), images, sum == null ? null : J.s(sum, "status", "0"),
                    List.of(new Ext.Variant(sku, sku, null, null, offer == null ? null : J.minorOrNull(offer, "price", "amount"), null, fa == null ? null : J.intOrNull(fa, "quantity"), sum == null ? null : J.s(sum, "productType")))));
        }
        String next = J.s(r, "pagination", "nextToken");
        return new Ext.Page<>(out, next);
    }

    private ObjectNode offerAttributes(Ctx c, ObjectNode attrs, Long priceMinor, Integer quantity) {
        String mp = marketplace(c);
        if (priceMinor != null) {
            ObjectNode o = ((ArrayNode) attrs.putArray("purchasable_offer")).addObject();
            o.put("marketplace_id", mp);
            o.put("currency", Providers.currencyOf(c.country()));
            o.putArray("our_price").addObject().putArray("schedule").addObject().put("value_with_tax", Double.parseDouble(ChannelAdapter.amount(priceMinor)));
        }
        if (quantity != null) attrs.putArray("fulfillment_availability").addObject().put("fulfillment_channel_code", "DEFAULT").put("quantity", Math.max(0, quantity));
        return attrs;
    }

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        String mp = marketplace(c);
        Map<String, String> ids = new LinkedHashMap<>();
        for (Ext.VariantDraft v : d.variants()) {
            ObjectNode body = HttpGateway.JSON.createObjectNode();
            body.put("productType", "PRODUCT");
            boolean hasBarcode = v.barcode() != null && !v.barcode().isBlank();
            body.put("requirements", hasBarcode ? "LISTING_OFFER_ONLY" : "LISTING");
            ObjectNode a = body.putObject("attributes");
            String suffix = v.options() == null || v.options().isEmpty() ? "" : " - " + String.join(" / ", v.options().values());
            a.putArray("item_name").addObject().put("value", d.title() + suffix).put("marketplace_id", mp).put("language_tag", "en_US");
            if (d.brand() != null) a.putArray("brand").addObject().put("value", d.brand()).put("marketplace_id", mp);
            if (hasBarcode) a.putArray("externally_assigned_product_identifier").addObject().put("type", v.barcode().length() == 12 ? "upc" : "ean").put("value", v.barcode()).put("marketplace_id", mp);
            a.putArray("condition_type").addObject().put("value", "new_new").put("marketplace_id", mp);
            if (d.description() != null && !d.description().isBlank()) a.putArray("product_description").addObject().put("value", d.description()).put("marketplace_id", mp).put("language_tag", "en_US");
            offerAttributes(c, a, v.priceMinor(), v.quantity());
            JsonNode r = call(c, "PUT", "/listings/2021-08-01/items/" + enc(sellerId(c)) + "/" + enc(v.sku()) + "?marketplaceIds=" + mp, body, "amazon-listings|gap=600").json();
            if ("INVALID".equals(J.s(r, "status"))) {
                JsonNode issue = r.path("issues").isArray() && r.path("issues").size() > 0 ? r.path("issues").get(0) : null;
                throw new HttpGateway.ApiException(422, "Amazon rejected " + v.sku() + ": " + (issue == null ? "invalid listing" : J.sOr(issue, "invalid listing", "message")));
            }
            ids.put(v.sku(), v.sku());
        }
        return new Ext.Created(d.variants().isEmpty() ? "" : d.variants().get(0).sku(), ids);
    }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        String mp = marketplace(c);
        String s = sku != null && !sku.isBlank() ? sku : variantId;
        String type = inventoryRef;   // the product type read when the listing was imported
        if (type == null || type.isBlank()) {
            JsonNode sum = call(c, "GET", "/listings/2021-08-01/items/" + enc(sellerId(c)) + "/" + enc(s) + "?marketplaceIds=" + mp + "&includedData=summaries", null, "amazon-listings|gap=600").json().path("summaries");
            type = sum.isArray() && sum.size() > 0 ? J.sOr(sum.get(0), "PRODUCT", "productType") : "PRODUCT";
        }
        ObjectNode body = HttpGateway.JSON.createObjectNode();
        body.put("productType", type);
        ArrayNode patches = body.putArray("patches");
        ObjectNode attrs = HttpGateway.JSON.createObjectNode();
        offerAttributes(c, attrs, priceMinor, quantity);
        attrs.fields().forEachRemaining((e) -> { ObjectNode p = patches.addObject(); p.put("op", "replace"); p.put("path", "/attributes/" + e.getKey()); p.set("value", e.getValue()); });
        if (patches.isEmpty()) return;
        JsonNode r = call(c, "PATCH", "/listings/2021-08-01/items/" + enc(sellerId(c)) + "/" + enc(s) + "?marketplaceIds=" + mp, body, "amazon-listings|gap=600").json();
        if ("INVALID".equals(J.s(r, "status"))) {
            JsonNode issue = r.path("issues").isArray() && r.path("issues").size() > 0 ? r.path("issues").get(0) : null;
            throw new HttpGateway.ApiException(422, "Amazon rejected the update: " + (issue == null ? "invalid" : J.sOr(issue, "invalid", "message")));
        }
    }

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        String mp = marketplace(c);
        Instant from = since == null ? Instant.now().minus(java.time.Duration.ofDays(30)) : since;
        String q = cursor == null ? "MarketplaceIds=" + mp + "&CreatedAfter=" + enc(from.toString()) + "&MaxResultsPerPage=50" : "MarketplaceIds=" + mp + "&NextToken=" + enc(cursor);
        JsonNode payload = call(c, "GET", "/orders/v0/orders?" + q, null, "amazon-orders|gap=2500").json().path("payload");
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(payload.path("Orders"))) {
            String id = J.s(o, "AmazonOrderId");
            List<Ext.Item> items = new ArrayList<>();
            try {
                for (JsonNode it : J.list(call(c, "GET", "/orders/v0/orders/" + enc(id) + "/orderItems", null, "amazon-order-items|gap=2100").json().path("payload").path("OrderItems"))) {
                    int qn = J.intOrNull(it, "QuantityOrdered") == null ? 1 : J.intOrNull(it, "QuantityOrdered");
                    long total = J.minor(it, "ItemPrice", "Amount");
                    items.add(new Ext.Item(J.s(it, "SellerSKU"), J.sOr(it, J.s(it, "ASIN"), "Title"), qn, qn == 0 ? 0 : total / qn, total, null));
                }
            } catch (HttpGateway.ApiException e) { /* the order is kept without its lines; the next sync fills them in */ }
            String st = J.sOr(o, "", "OrderStatus");
            String stage = switch (st) { case "PartiallyShipped" -> "PROCESSING"; case "Shipped" -> "SHIPPED"; case "Canceled", "Unfulfillable" -> "CANCELLED"; default -> "NEW"; };
            JsonNode a = o.path("ShippingAddress");
            Map<String, Object> addr = new LinkedHashMap<>();
            addr.put("line1", J.s(a, "AddressLine1")); addr.put("city", J.s(a, "City")); addr.put("region", J.s(a, "StateOrRegion")); addr.put("country", J.s(a, "CountryCode")); addr.put("zip", J.s(a, "PostalCode"));
            out.add(new Ext.Order(id, id, st, stage, J.minor(o, "OrderTotal", "Amount"), J.sOr(o, Providers.currencyOf(c.country()), "OrderTotal", "CurrencyCode"), J.sOr(a, J.s(o, "BuyerInfo", "BuyerName"), "Name"), J.s(a, "Phone"), J.s(o, "BuyerInfo", "BuyerEmail"), addr, items, J.time(o, "PurchaseDate"), null, null));
        }
        return new Ext.Page<>(out, J.s(payload, "NextToken"));
    }

    @Override
    public List<Ext.Action> actions(Ext.Order o) {
        return "NEW".equals(o.stage()) || "PROCESSING".equals(o.stage()) ? List.of(new Ext.Action("ship", "Confirm shipment", true)) : List.of();
    }

    @Override
    public void act(Ctx c, String id, String action, Map<String, String> params) {
        if (!"ship".equals(action)) throw new HttpGateway.ApiException(400, "Unknown action");
        String tracking = params.get("trackingNumber");
        if (tracking == null || tracking.isBlank()) throw new HttpGateway.ApiException(400, "A tracking number is required to confirm shipment on Amazon");
        ArrayNode items = HttpGateway.JSON.createArrayNode();
        for (JsonNode it : J.list(call(c, "GET", "/orders/v0/orders/" + enc(id) + "/orderItems", null, "amazon-order-items|gap=2100").json().path("payload").path("OrderItems")))
            items.addObject().put("orderItemId", J.s(it, "OrderItemId")).put("quantity", J.intOrNull(it, "QuantityOrdered") == null ? 1 : J.intOrNull(it, "QuantityOrdered"));
        ObjectNode pkg = HttpGateway.JSON.createObjectNode();
        pkg.put("packageReferenceId", 1);
        pkg.put("carrierCode", params.getOrDefault("carrier", "Other"));
        pkg.put("carrierName", params.getOrDefault("carrier", "Other"));
        pkg.put("trackingNumber", tracking);
        pkg.put("shipDate", Instant.now().toString());
        pkg.set("orderItems", items);
        call(c, "POST", "/orders/v0/orders/" + enc(id) + "/shipmentConfirmation", Map.of("marketplaceId", marketplace(c), "packageDetail", pkg), "amazon-orders|gap=2500");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
