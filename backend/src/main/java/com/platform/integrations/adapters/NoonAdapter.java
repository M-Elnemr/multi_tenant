package com.platform.integrations.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.integrations.ChannelAdapter;
import com.platform.integrations.Ext;
import com.platform.integrations.HttpGateway;
import com.platform.integrations.HttpGateway.Req;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * noon Partner API (noon-docs.noonpartners.dev). Auth: an RS256 JWT signed with the service account's private key is exchanged at
 * /identity/public/v1/api/login for session cookies. Implemented from noon's published guides: price and stock for products that already exist on noon
 * (matched by your partner SKU) and the order list of a partner-fulfilled (FBPI) warehouse. noon offers no product-list endpoint to partners and creating
 * products needs category attributes, so new products are created in noon Seller Lab and then linked by SKU. BETA until verified on a live account.
 */
@Component
public class NoonAdapter implements ChannelAdapter {
    static final String BASE = "https://noon-api-gateway.noon.partners";
    private record Session(String cookie, long expires) {}
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    @Override public String provider() { return "NOON"; }

    // ---- auth -----------------------------------------------------------------------------------------------

    static String jwt(String keyId, String privateKeyPem, Instant now) {
        try {
            String pem = privateKeyPem.replace("\\n", "\n");
            if (pem.contains("BEGIN RSA PRIVATE KEY")) throw new HttpGateway.ApiException(400, "The private key must be in PKCS#8 form (-----BEGIN PRIVATE KEY-----), as in the key file noon gives you");
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            String h = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String p = enc.encodeToString(("{\"sub\":\"" + keyId + "\",\"iat\":" + now.getEpochSecond() + ",\"jti\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update((h + "." + p).getBytes(StandardCharsets.UTF_8));
            return h + "." + p + "." + enc.encodeToString(sig.sign());
        } catch (HttpGateway.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpGateway.ApiException(400, "The private key could not be read");
        }
    }

    private String login(Ctx c) {
        String keyId = c.need("keyId");
        HttpGateway.Res r = c.http().send(Req.json("POST", BASE + "/identity/public/v1/api/login", Map.of("Accept", "application/json"),
                Map.of("token", jwt(keyId, c.need("privateKey"), Instant.now()), "default_project_code", c.need("projectCode")), c.rateKey()));
        if (!r.ok()) throw new HttpGateway.ApiException(r.status() == 404 ? 401 : r.status(), "noon did not accept these credentials (" + HttpGateway.describe(r) + ")");
        List<String> cookies = new ArrayList<>();
        for (var e : r.headers().entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie")) for (String sc : e.getValue()) cookies.add(sc.split(";", 2)[0]);
        if (cookies.isEmpty()) throw new HttpGateway.ApiException(502, "noon did not start a session");
        String cookie = String.join("; ", cookies);
        SESSIONS.put(keyId, new Session(cookie, System.currentTimeMillis() + 20 * 60_000));
        return cookie;
    }

    private String cookie(Ctx c) {
        Session s = SESSIONS.get(c.need("keyId"));
        return s != null && s.expires() > System.currentTimeMillis() ? s.cookie() : login(c);
    }

    private HttpGateway.Res post(Ctx c, String path, Object body) {
        for (int attempt = 0; ; attempt++) {
            Map<String, String> h = Map.of("Cookie", cookie(c), "Accept", "application/json");
            HttpGateway.Res r = c.http().send(Req.json("POST", BASE + path, h, body, c.rateKey()));
            if ((r.status() == 401 || r.status() == 403) && attempt == 0) { SESSIONS.remove(c.need("keyId")); continue; }
            if (!r.ok()) throw new HttpGateway.ApiException(r.status(), HttpGateway.describe(r));
            return r;
        }
    }

    @Override
    public Ext.AccountInfo connect(Ctx c) {
        String cookie = login(c);
        HttpGateway.Res who = c.http().send(Req.get(BASE + "/identity/v1/whoami", Map.of("Cookie", cookie, "Accept", "application/json"), c.rateKey()));
        if (!who.ok()) throw new HttpGateway.ApiException(who.status(), "noon did not accept these credentials (" + HttpGateway.describe(who) + ")");
        return new Ext.AccountInfo(c.need("projectCode"), "noon " + c.need("projectCode"), com.platform.integrations.Providers.currencyOf(c.country()), null);
    }

    // ---- catalogue ------------------------------------------------------------------------------------------

    @Override
    public Ext.Page<Ext.Product> listProducts(Ctx c, String cursor) { return new Ext.Page<>(List.of(), null); }   // noon offers partners no product list

    @Override
    public Ext.Created createProduct(Ctx c, Ext.ProductDraft d) {
        throw new HttpGateway.ApiException(400, "New products are created in noon Seller Lab; then publish them here to link them by SKU and set their price and stock");
    }

    private static String cc(Ctx c) { return c.country().toLowerCase(); }

    @Override
    public void updateOffer(Ctx c, String productId, String variantId, String sku, String inventoryRef, Long priceMinor, Long compareAtMinor, Integer quantity) {
        String partnerSku = sku != null && !sku.isBlank() ? sku : productId;
        if (priceMinor != null) {
            ObjectNode item = HttpGateway.JSON.createObjectNode();
            item.put("partner_sku", partnerSku);
            item.put("country_code", cc(c));
            item.put("price", Double.parseDouble(ChannelAdapter.amount(priceMinor)));
            if (compareAtMinor != null && compareAtMinor > priceMinor) item.put("msrp", Double.parseDouble(ChannelAdapter.amount(compareAtMinor)));
            check(post(c, "/pricing/v1/pricing/upsert", Map.of("items", List.of(item))).json(), "price");
        }
        if (quantity != null) {
            String wh = c.opt("warehouseCode");
            if (wh == null) throw new HttpGateway.ApiException(400, "Add your noon warehouse code to this account to update stock");
            check(post(c, "/stock/v1/stock-update", Map.of("items", List.of(Map.of("warehouse_code", wh, "partner_sku", partnerSku, "qty", Math.max(0, quantity))))).json(), "stock");
        }
    }

    /** noon answers 200 even when one SKU failed: each item carries its own status. */
    private static void check(JsonNode r, String what) {
        for (JsonNode i : J.list(r.path("items"))) {
            if (!"OK".equals(J.s(i, "status", "status_code")))
                throw new HttpGateway.ApiException(422, "noon could not update " + what + " for " + J.s(i, "partner_sku") + ": " + J.sOr(i, J.sOr(i, "rejected", "status", "status_code"), "status", "message"));
        }
    }

    // ---- orders (partner-fulfilled warehouses) --------------------------------------------------------------

    @Override
    public Ext.Page<Ext.Order> listOrders(Ctx c, Instant since, String cursor) {
        String wh = c.opt("warehouseCode");
        if (wh == null) return new Ext.Page<>(List.of(), null);
        ObjectNode body = HttpGateway.JSON.createObjectNode();
        body.put("warehouse_code", wh);
        if (since != null) body.put("created_after", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(since));
        HttpGateway.Res r;
        try {
            r = post(c, "/fbpi/v1/fbpi-orders/list" + (cursor == null ? "" : "?next_token=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8)), body);
        } catch (HttpGateway.ApiException e) {
            if (e.status == 403 || e.status == 404) throw new HttpGateway.ApiException(403, "Orders are only available for noon partner-fulfilled (FBPI) warehouses");
            throw e;
        }
        List<Ext.Order> out = new ArrayList<>();
        for (JsonNode o : J.list(r.json().path("orders"))) {
            List<Ext.Item> items = new ArrayList<>();
            long total = 0;
            boolean anyShipped = false, allCancelled = true;
            for (JsonNode it : J.list(o.path("items"))) {
                long price = J.minor(it, "delivered_invoice_price");
                total += price;
                if ("INTEGRATION_ITEM_STATUS_SHIPPED".equals(J.s(it, "integration_status"))) anyShipped = true;
                if (!"MP_ITEM_STATUS_CANCELLED".equals(J.s(it, "mp_status"))) allCancelled = false;
                items.add(new Ext.Item(J.s(it, "partner_sku"), J.s(it, "partner_sku"), 1, price, price, null));
            }
            String stage = allCancelled && !items.isEmpty() ? "CANCELLED" : anyShipped ? "SHIPPED" : "NEW";
            out.add(new Ext.Order(J.s(o, "fbpi_order_nr"), J.sOr(o, J.s(o, "fbpi_order_nr"), "mp_order_nr"), stage, stage, total, J.sOr(o, com.platform.integrations.Providers.currencyOf(c.country()), "currency_code").toUpperCase(), null, null, null,
                    Map.of("country", J.sOr(o, "", "customer_country_code")), items, J.time(o, "order_created_at"), null, null));
        }
        String next = J.s(r.json(), "next_token");
        return new Ext.Page<>(out, next == null || next.isBlank() ? null : next);
    }
}
