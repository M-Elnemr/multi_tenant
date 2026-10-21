package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The Open Store: one marketplace over every shop, and orders that land in the shop they were bought from. */
class OpenStoreIntegrationTest extends IntegrationTestBase {
    static final String OPEN = "open.platform.test";
    @Autowired JdbcClient jdbc;

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String variant, String productSlug, String productId) {}

    Shop shop(String productName, long price) throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","variants":[{"sku":"T-%s","priceMinor":%d,"stock":[{"branchId":"%s","quantity":20}]}]}
                """.formatted(productName, uniq(), price, branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"FIXED\",\"name\":\"Delivery\",\"feeMinor\":3000}").andExpect(status().isCreated());
        return new Shop(t, JsonPath.read(p, "$.variants[0].id"), JsonPath.read(p, "$.slug"), JsonPath.read(p, "$.id"));
    }

    String cart(Shop a, int qty) {
        return """
                {"items":[{"variantId":"%s","quantity":%d}],
                 "address":{"recipientName":"Mona","phone":"01012345678","addressLine1":"1 Tahrir St","governorateCode":"CAI","city":"Cairo"}}
                """.formatted(a.variant(), qty);
    }

    /** Signs a shop client in on the marketplace with the same Google identity the shops use (the test verifier accepts "test|sub|email|name"). */
    String openToken(String sub) throws Exception {
        String res = ok(mvc.perform(post("/api/v1/open/auth/google").header("Host", OPEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"test|%s|%s@example.com|Mona Client\"}".formatted(sub, sub))).andExpect(status().isOk()));
        return JsonPath.read(res, "$.token");
    }

    @Test
    void marketplaceHostResolvesAndListsEveryLiveShop() throws Exception {
        Shop a = shop("Mega Tee " + uniq(), 20000), b = shop("Mega Hoodie " + uniq(), 50000);
        onHost(OPEN, null, "GET", "/api/v1/tenant/resolve", null).andExpect(jsonPath("$.kind").value("OPEN"));
        onHost(OPEN, null, "GET", "/api/v1/shop/products", null).andExpect(status().isNotFound());   // the marketplace has no shop of its own

        String all = ok(onHost(OPEN, null, "GET", "/api/v1/open/products?pageSize=100", null).andExpect(status().isOk()));
        List<String> slugs = JsonPath.read(all, "$.data[*].slug");
        assertThat(slugs).contains(a.productSlug(), b.productSlug());
        onHost(OPEN, null, "GET", "/api/v1/open/products?shop=" + a.t().slug(), null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].shop.slug").value(a.t().slug()));
        onHost(OPEN, null, "GET", "/api/v1/open/products?shop=" + a.t().slug() + "&minPrice=30000", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(OPEN, null, "GET", "/api/v1/open/products?shop=" + b.t().slug() + "&minPrice=30000&sort=price_asc", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(OPEN, null, "GET", "/api/v1/open/products?q=hoodie&shop=" + b.t().slug(), null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(OPEN, null, "GET", "/api/v1/open/products/facets?shop=" + a.t().slug(), null).andExpect(jsonPath("$.brands[0].brand").value("Acme")).andExpect(jsonPath("$.shops[0].shopSlug").value(a.t().slug()));
        onHost(OPEN, null, "GET", "/api/v1/open/suggest?q=mega", null).andExpect(status().isOk());
        onHost(OPEN, null, "GET", "/api/v1/open/home", null).andExpect(status().isOk()).andExpect(jsonPath("$.stats.shops").isNumber());
        onHost(OPEN, null, "GET", "/api/v1/open/categories", null).andExpect(status().isOk());
        onHost(OPEN, null, "GET", "/api/v1/open/shops?q=" + a.t().slug(), null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(OPEN, null, "GET", "/api/v1/open/products/" + a.t().slug() + "/" + a.productSlug(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.shop.slug").value(a.t().slug())).andExpect(jsonPath("$.variants[0].id").value(a.variant()));

        // a shop the admin hides, or one on vacation, disappears from the marketplace but its own site still works
        jdbc.sql("UPDATE commerce.store_profiles SET open_listed = FALSE WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", a.t().slug()).update();
        onHost(OPEN, null, "GET", "/api/v1/open/products?shop=" + a.t().slug(), null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(a.t().host(), null, "GET", "/api/v1/shop/products", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(b.t().host(), b.t().access(), "PATCH", "/api/v1/store/profile", "{\"isOpen\":false}").andExpect(status().isOk());
        onHost(OPEN, null, "GET", "/api/v1/open/products?shop=" + b.t().slug(), null).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void anOrderLandsInTheShopLikeAnyOtherAndGuestsCanTrackIt() throws Exception {
        Shop a = shop("Cart Tee " + uniq(), 20000);
        onHost(OPEN, null, "POST", "/api/v1/open/cart/quote", cart(a, 2)).andExpect(status().isOk()).andExpect(jsonPath("$.shop.slug").value(a.t().slug()))
                .andExpect(jsonPath("$.subtotalMinor").value(40000)).andExpect(jsonPath("$.shippingMinor").value(3000)).andExpect(jsonPath("$.totalMinor").value(43000)).andExpect(jsonPath("$.shippingKnown").value(true));
        onHost(OPEN, null, "POST", "/api/v1/open/cart/quote", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(a.variant())).andExpect(jsonPath("$.shippingKnown").value(false)).andExpect(jsonPath("$.totalMinor").value(20000));

        String key = "k-" + uniq();
        String res = ok(onHost(OPEN, null, "POST", "/api/v1/open/checkout", cart(a, 2), key).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.shop.slug").value(a.t().slug())));
        String number = JsonPath.read(res, "$.orderNumber");
        onHost(OPEN, null, "POST", "/api/v1/open/checkout", cart(a, 2), key).andExpect(status().isCreated()).andExpect(jsonPath("$.orderNumber").value(number));   // same request again: same order
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", a.t().slug()).query(Long.class).single()).isEqualTo(1);

        // the shop sees an ordinary REQUESTED order, exactly as from its own site, and no commission is involved
        onHost(a.t().host(), a.t().access(), "GET", "/api/v1/store/orders", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].status").value("REQUESTED"));
        assertThat(jdbc.sql("SELECT source FROM commerce.orders WHERE order_number = :n AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("n", number).param("s", a.t().slug()).query(String.class).single()).isEqualTo("OPEN");

        String track = "{\"shop\":\"%s\",\"orderNumber\":\"%s\",\"phone\":\"%s\"}";
        onHost(OPEN, null, "POST", "/api/v1/open/orders/track", track.formatted(a.t().slug(), number, "01012345678")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").isNotEmpty()).andExpect(jsonPath("$.totalMinor").value(43000));
        onHost(OPEN, null, "POST", "/api/v1/open/orders/track", track.formatted(a.t().slug(), number, "01000000000")).andExpect(status().isNotFound());
    }

    @Test
    void aCartCannotMixShopsAndABadCartPlacesNothing() throws Exception {
        Shop a = shop("Mix Tee " + uniq(), 20000), b = shop("Mix Hoodie " + uniq(), 50000);
        String both = """
                {"items":[{"variantId":"%s","quantity":1},{"variantId":"%s","quantity":1}],
                 "address":{"recipientName":"Mona","phone":"01012345678","addressLine1":"1 Tahrir St","governorateCode":"CAI","city":"Cairo"}}
                """.formatted(a.variant(), b.variant());
        onHost(OPEN, null, "POST", "/api/v1/open/cart/quote", both).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CART_MULTI_SHOP"));
        onHost(OPEN, null, "POST", "/api/v1/open/checkout", both, "k-" + uniq()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CART_MULTI_SHOP"));
        onHost(a.t().host(), a.t().access(), "GET", "/api/v1/store/orders", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(b.t().host(), b.t().access(), "GET", "/api/v1/store/orders", null).andExpect(jsonPath("$.meta.total").value(0));

        jdbc.sql("UPDATE commerce.store_profiles SET is_open = FALSE WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", b.t().slug()).update();
        onHost(OPEN, null, "POST", "/api/v1/open/checkout", cart(b, 1), "k-" + uniq()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SHOP_UNAVAILABLE"));
        onHost(OPEN, null, "POST", "/api/v1/open/checkout", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(a.variant())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADDRESS_REQUIRED"));
        onHost(OPEN, null, "POST", "/api/v1/open/checkout", cart(a, 1).replace(a.variant(), UUID.randomUUID().toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
    }

    @Test
    void shopClientsSignInWithGoogleOrderAndReviewAcrossShops() throws Exception {
        Shop a = shop("Acct Tee " + uniq(), 20000), b = shop("Acct Hoodie " + uniq(), 50000);
        String sub = "g" + uniq();
        String token = openToken(sub);
        mvc.perform(get("/api/v1/open/orders").header("Host", OPEN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/open/me").header("Host", OPEN).header("X-Open-Token", "nonsense")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/open/me").header("Host", OPEN).header("X-Open-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mona Client")).andExpect(jsonPath("$.phone").doesNotExist());

        // it is the very same account a shop knows: signing in on a shop with the same Google identity reaches the same client
        String shopToken = googleClient(a.t().host(), sub, "Mona Client");
        onHost(a.t().host(), shopToken, "GET", "/api/v1/shop/me", null).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Mona Client"));

        // an order in each shop (a cart holds one shop); the phone typed at shipping is saved on the account
        for (Shop s : List.of(a, b)) mvc.perform(post("/api/v1/open/checkout").header("Host", OPEN).header("X-Open-Token", token).contentType(MediaType.APPLICATION_JSON).content(cart(s, 1))).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/open/me").header("Host", OPEN).header("X-Open-Token", token)).andExpect(jsonPath("$.phone").value("+201012345678"));
        onHost(a.t().host(), shopToken, "GET", "/api/v1/shop/me", null).andExpect(jsonPath("$.phone").value("+201012345678"));   // and the shop sees it too
        mvc.perform(get("/api/v1/open/orders").header("Host", OPEN).header("X-Open-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(2)).andExpect(jsonPath("$.data[0].items[0].name").isNotEmpty());

        String review = "{\"productId\":\"%s\",\"rating\":5,\"text\":\"Great\"}".formatted(a.productId());
        mvc.perform(post("/api/v1/open/reviews").header("Host", OPEN).header("X-Open-Token", token).contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isForbidden());   // not received yet
        String orderId = jdbc.sql("SELECT id::text FROM commerce.orders WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", a.t().slug()).query(String.class).single();
        for (String st : new String[]{"PREPARING", "SHIPPED", "ARRIVED"}) onHost(a.t().host(), a.t().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"" + st + "\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/open/orders/" + orderId).header("Host", OPEN).header("X-Open-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARRIVED"));
        mvc.perform(post("/api/v1/open/reviews").header("Host", OPEN).header("X-Open-Token", token).contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/open/reviews").header("Host", OPEN).header("X-Open-Token", token).contentType(MediaType.APPLICATION_JSON).content(review)).andExpect(status().isConflict());
        String rid = JsonPath.read(ok(onHost(a.t().host(), a.t().access(), "GET", "/api/v1/store/reviews", null)), "$.data[0].id");
        onHost(a.t().host(), a.t().access(), "POST", "/api/v1/store/reviews/" + rid + "/status", "{\"status\":\"APPROVED\"}").andExpect(status().isOk());
        onHost(OPEN, null, "GET", "/api/v1/open/products/" + a.t().slug() + "/" + a.productSlug(), null).andExpect(jsonPath("$.reviews.count").value(1));

        // someone else's order is not reachable
        String other = openToken("g" + uniq());
        mvc.perform(get("/api/v1/open/orders/" + orderId).header("Host", OPEN).header("X-Open-Token", other)).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/open/auth/logout").header("Host", OPEN).header("X-Open-Token", token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/open/me").header("Host", OPEN).header("X-Open-Token", token)).andExpect(status().isUnauthorized());
    }
}
