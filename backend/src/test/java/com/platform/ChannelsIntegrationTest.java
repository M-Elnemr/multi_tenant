package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.integrations.SyncService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Sales channels: connecting, importing, publishing with per-channel price/quantity, orders and their actions, insights. The platforms are faked. */
class ChannelsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired SyncService sync;

    @BeforeEach
    void clean() { FakeHttpGateway.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String productId, String variantId, String sku) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String sku = "SKU-" + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","variants":[{"sku":"%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(uniq(), sku, branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        return new Shop(t, JsonPath.read(p, "$.id"), JsonPath.read(p, "$.variants[0].id"), sku);
    }

    void drain() { while (sync.runOne()) { /* run every queued job */ } }

    String shopify(Shop s) throws Exception {
        FakeHttpGateway.when("GET", "/admin/api/2024-10/shop.json", 200, "{\"shop\":{\"id\":123,\"name\":\"My Shopify\",\"currency\":\"EGP\"}}");
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts",
                "{\"provider\":\"SHOPIFY\",\"country\":\"EG\",\"credentials\":{\"shopDomain\":\"my-shop.myshopify.com\",\"accessToken\":\"shpat_secret_token\"}}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("SHOPIFY")).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.label").value("My Shopify")));
        assertThat(res).doesNotContain("shpat_secret_token").doesNotContain("credentials");   // credentials never come back
        return JsonPath.read(res, "$.id");
    }

    @Test
    void catalogIsFilteredByCountryAndCarriersAreSeparate() throws Exception {
        Tenant t = onboard("STORE");
        String eg = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/catalog?country=EG&kind=STORE_PLATFORM", null).andExpect(status().isOk()));
        List<String> ids = JsonPath.read(eg, "$[*].id");
        assertThat(ids).contains("SHOPIFY", "WOOCOMMERCE", "YOUCAN").doesNotContain("SALLA");
        String sa = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/catalog?country=SA", null));
        assertThat((List<String>) JsonPath.read(sa, "$[*].id")).contains("SALLA", "AMAZON", "NOON", "SMSA", "NAQEL").doesNotContain("BOSTA", "MYLERZ");
        String egMarket = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/catalog?country=EG&kind=MARKETPLACE", null));
        assertThat((List<String>) JsonPath.read(egMarket, "$[*].id")).containsExactlyInAnyOrder("AMAZON", "NOON");
    }

    @Test
    void connectingChecksTheCredentialsAndRejectsUnsafeAddresses() throws Exception {
        Shop s = shop();
        FakeHttpGateway.when("GET", "/shop.json", 401, "{\"errors\":\"[API] Invalid API key or access token\"}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"SHOPIFY\",\"country\":\"EG\",\"credentials\":{\"shopDomain\":\"x.myshopify.com\",\"accessToken\":\"bad\"}}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CONNECTION_FAILED"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"SHOPIFY\",\"country\":\"EG\",\"credentials\":{\"shopDomain\":\"evil.example.com\",\"accessToken\":\"abc\"}}")
                .andExpect(status().isUnprocessableEntity());   // only {shop}.myshopify.com
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"WOOCOMMERCE\",\"country\":\"EG\",\"credentials\":{\"siteUrl\":\"https://localhost\",\"consumerKey\":\"ck\",\"consumerSecret\":\"cs\"}}")
                .andExpect(status().isUnprocessableEntity());   // never our own network
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"SALLA\",\"country\":\"EG\",\"credentials\":{\"accessToken\":\"x\"}}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COUNTRY_NOT_SUPPORTED"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"SMSA\",\"country\":\"SA\",\"credentials\":{}}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
        assertThat(FakeHttpGateway.calls("GET", "localhost")).isEmpty();
    }

    @Test
    void importLinksBySkuThenEditPushesPriceAndQuantityToThePlatform() throws Exception {
        Shop s = shop();
        String account = shopify(s);
        FakeHttpGateway.when("GET", "/products.json", 200, """
                {"products":[{"id":900,"title":"Imported Tee","body_html":"<p>nice</p>","vendor":"Acme","status":"active","images":[{"src":"https://cdn.example.com/a.jpg"}],
                  "variants":[{"id":901,"sku":"%s","barcode":"123","title":"Default Title","price":"250.00","compare_at_price":"300.00","inventory_quantity":4,"inventory_item_id":777}]},
                 {"id":910,"title":"Other","vendor":"X","status":"active","images":[],"variants":[{"id":911,"sku":"NOT-IN-SHOP","title":"Default Title","price":"10.00","inventory_quantity":1,"inventory_item_id":778}]}]}
                """.formatted(s.sku()));
        FakeHttpGateway.when("GET", "/orders.json", 200, "{\"orders\":[]}");
        drain();

        String list = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + account, null).andExpect(jsonPath("$.meta.total").value(2)));
        assertThat((List<Object>) JsonPath.read(list, "$.data[?(@.externalSku=='" + s.sku() + "')].variantId")).containsExactly(s.variantId());   // linked by SKU
        assertThat((List<Object>) JsonPath.read(list, "$.data[?(@.externalSku=='NOT-IN-SHOP')].variantId")).containsNull();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + account + "&filter=unlinked", null).andExpect(jsonPath("$.meta.total").value(1));
        String listingId = JsonPath.<List<String>>read(list, "$.data[?(@.externalSku=='" + s.sku() + "')].id").get(0);

        // the platform has its own price and quantity; changing them pushes to the platform first
        FakeHttpGateway.when("PUT", "/variants/901.json", 200, "{}");
        FakeHttpGateway.when("GET", "/locations.json", 200, "{\"locations\":[{\"id\":55,\"active\":true}]}");
        FakeHttpGateway.when("POST", "/inventory_levels/set.json", 200, "{}");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/integrations/listings/" + listingId, "{\"priceMinor\":27500,\"quantity\":2}").andExpect(status().isOk()).andExpect(jsonPath("$.priceMinor").value(27500)).andExpect(jsonPath("$.quantity").value(2));
        assertThat(FakeHttpGateway.calls("PUT", "/variants/901.json").get(0).body()).contains("\"price\":\"275.00\"");
        assertThat(FakeHttpGateway.calls("POST", "/inventory_levels/set.json").get(0).body()).contains("\"available\":2").contains("\"inventory_item_id\":777");
        assertThat(jdbc.sql("SELECT price_minor FROM commerce.product_variants WHERE id = CAST(:v AS uuid)").param("v", s.variantId()).query(Long.class).single()).isEqualTo(20000);   // the shop's own price is untouched

        // a refusal is reported and remembered on the listing
        FakeHttpGateway.reset();
        FakeHttpGateway.when("PUT", "/variants/901.json", 422, "{\"errors\":{\"price\":[\"is invalid\"]}}");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/integrations/listings/" + listingId, "{\"priceMinor\":1}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CHANNEL_REJECTED"));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + account + "&filter=error", null).andExpect(jsonPath("$.meta.total").value(1));

        // bring an unlinked channel product into the shop as a draft, linked
        String unlinked = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + account + "&filter=unlinked", null));
        String otherId = JsonPath.read(unlinked, "$.data[0].id");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts/" + account + "/import-to-shop", "{\"listingIds\":[\"" + otherId + "\"]}").andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + account + "&filter=unlinked", null).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void ordersAreImportedAndTheOwnerCanActOnThemOnThePlatform() throws Exception {
        Shop s = shop();
        String account = shopify(s);
        FakeHttpGateway.when("GET", "/products.json", 200, "{\"products\":[]}");
        FakeHttpGateway.when("GET", "/orders.json", 200, """
                {"orders":[{"id":5001,"name":"#1001","financial_status":"paid","fulfillment_status":null,"total_price":"550.00","currency":"EGP","created_at":"2026-10-20T10:00:00+02:00",
                  "customer":{"first_name":"Mona","last_name":"A","email":"m@x.com"},"shipping_address":{"name":"Mona A","phone":"+201012345678","address1":"1 Tahrir","city":"Cairo","country":"Egypt"},
                  "line_items":[{"title":"Tee","sku":"T1","quantity":2,"price":"275.00"}],"fulfillments":[]},
                 {"id":5002,"name":"#1002","financial_status":"paid","fulfillment_status":"fulfilled","total_price":"100.00","currency":"EGP","created_at":"2026-10-21T10:00:00+02:00","line_items":[],"fulfillments":[{"tracking_number":"TRK1","tracking_company":"Bosta","shipment_status":"delivered"}]}]}
                """);
        drain();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?accountId=" + account, null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders/summary", null).andExpect(jsonPath("$.NEW").value(1)).andExpect(jsonPath("$.DELIVERED").value(1));
        String newOrder = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?stage=NEW&q=Mona", null).andExpect(jsonPath("$.meta.total").value(1)));
        String id = JsonPath.read(newOrder, "$.data[0].id");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders/" + id, null).andExpect(status().isOk()).andExpect(jsonPath("$.number").value("#1001")).andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.address.city").value("Cairo")).andExpect(jsonPath("$.actions[0].key").value("fulfill")).andExpect(jsonPath("$.totalMinor").value(55000));

        FakeHttpGateway.when("GET", "/orders/5001/fulfillment_orders.json", 200, "{\"fulfillment_orders\":[{\"id\":42,\"status\":\"open\"}]}");
        FakeHttpGateway.when("POST", "/fulfillments.json", 201, "{}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/orders/" + id + "/actions", "{\"action\":\"fulfill\",\"params\":{\"trackingNumber\":\"BO999\",\"carrier\":\"Bosta\"}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("SHIPPED")).andExpect(jsonPath("$.trackingNumber").value("BO999"));
        assertThat(FakeHttpGateway.calls("POST", "/fulfillments.json").get(0).body()).contains("\"fulfillment_order_id\":42").contains("BO999");

        // customers are built from the orders; the other shop sees nothing of this
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/customers", null).andExpect(jsonPath("$.meta.total").value(2));
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/integrations/orders/" + id, null).andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/integrations/accounts/" + account, null).andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/integrations/orders", null).andExpect(jsonPath("$.meta.total").value(0));

        String ins = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/insights?days=365", null).andExpect(status().isOk()));
        assertThat((Number) JsonPath.read(ins, "$.totals.orders")).isEqualTo(2);
        assertThat(JsonPath.<Number>read(ins, "$.totals.revenueMinor").longValue()).isEqualTo(65000L);
        assertThat((List<Object>) JsonPath.read(ins, "$.topProducts")).isNotEmpty();
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/insights?days=30&accountId=" + account, null).andExpect(jsonPath("$.channels.length()").value(1));
    }

    @Test
    void publishingSendsEachChannelItsOwnPriceAndQuantityAndCanBeDoneLater() throws Exception {
        Shop s = shop();
        String shopifyAcc = shopify(s);
        FakeHttpGateway.when("GET", "/system_status", 200, "{}");
        FakeHttpGateway.when("GET", "/wc/v3/products?per_page=1", 200, "[]");
        FakeHttpGateway.when("GET", "/data/currencies/current", 200, "{\"code\":\"EGP\"}");
        String wooAcc = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts",
                "{\"provider\":\"WOOCOMMERCE\",\"country\":\"EG\",\"credentials\":{\"siteUrl\":\"https://example.com\",\"consumerKey\":\"ck_1\",\"consumerSecret\":\"cs_1\"}}").andExpect(status().isCreated())), "$.id");

        String plan = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/publish-plan?productId=" + s.productId(), null).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(plan, "$.accounts[*].id")).containsExactlyInAnyOrder(shopifyAcc, wooAcc);
        assertThat((Number) JsonPath.read(plan, "$.variants[0].available")).isEqualTo(9);

        FakeHttpGateway.when("POST", "/admin/api/2024-10/products.json", 201, "{\"product\":{\"id\":3000,\"variants\":[{\"id\":3001,\"sku\":\"" + s.sku() + "\",\"inventory_item_id\":3002}]}}");
        FakeHttpGateway.when("GET", "/locations.json", 200, "{\"locations\":[{\"id\":55,\"active\":true}]}");
        FakeHttpGateway.when("POST", "/inventory_levels/set.json", 200, "{}");
        FakeHttpGateway.when("POST", "/wp-json/wc/v3/products", 201, "{\"id\":4000}");
        String body = """
                {"productId":"%s","targets":[
                  {"accountId":"%s","variants":[{"variantId":"%s","priceMinor":23000,"quantity":3}]},
                  {"accountId":"%s","variants":[{"variantId":"%s","priceMinor":21000,"compareAtMinor":26000,"quantity":7}]}]}
                """.formatted(s.productId(), shopifyAcc, s.variantId(), wooAcc, s.variantId());
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/publish", body).andExpect(status().isOk()));
        assertThat((List<Boolean>) JsonPath.read(res, "$[*].ok")).containsOnly(true);
        assertThat(FakeHttpGateway.calls("POST", "/admin/api/2024-10/products.json").get(0).body()).contains("\"price\":\"230.00\"").contains(s.sku());
        assertThat(FakeHttpGateway.calls("POST", "/inventory_levels/set.json").get(0).body()).contains("\"available\":3");
        String woo = FakeHttpGateway.calls("POST", "/wp-json/wc/v3/products").get(0).body();
        assertThat(woo).contains("\"regular_price\":\"260.00\"").contains("\"sale_price\":\"210.00\"").contains("\"stock_quantity\":7");   // a compare-at price shows as a sale

        // now both channels know the product, each with its own numbers
        String after = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/publish-plan?productId=" + s.productId(), null));
        assertThat((List<Object>) JsonPath.read(after, "$.accounts[*].listings[*]")).hasSize(2);
        assertThat((List<Integer>) JsonPath.read(after, "$.accounts[*].listings[*].quantity")).containsExactlyInAnyOrder(3, 7);

        // one channel failing does not stop the other
        FakeHttpGateway.reset();
        FakeHttpGateway.when("PUT", "/variants/3001.json", 200, "{}");
        FakeHttpGateway.when("GET", "/variants/3001.json", 200, "{\"variant\":{\"id\":3001,\"inventory_item_id\":3002}}");
        FakeHttpGateway.when("POST", "/inventory_levels/set.json", 200, "{}");
        FakeHttpGateway.when("GET", "/locations.json", 200, "{\"locations\":[{\"id\":55,\"active\":true}]}");
        FakeHttpGateway.when("PUT", "/products/4000", 400, "{\"message\":\"Invalid price\"}");
        String again = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/publish", body).andExpect(status().isOk()));
        assertThat((List<Boolean>) JsonPath.read(again, "$[*].ok")).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void anExpiredSallaTokenIsRefreshedAndTheNewTokensAreKept() throws Exception {
        Shop s = shop();
        FakeHttpGateway.once("GET", "/admin/v2/store/info", 200, "{\"data\":{\"id\":77,\"name\":\"متجر سلة\",\"domain\":\"salla.sa/x\"}}");
        String acc = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", "{\"provider\":\"SALLA\",\"country\":\"SA\",\"credentials\":{\"accessToken\":\"old-access\",\"refreshToken\":\"old-refresh\"}}").andExpect(status().isCreated())), "$.id");
        FakeHttpGateway.reset();
        FakeHttpGateway.once("GET", "/admin/v2/products", 401, "{\"message\":\"Unauthenticated\"}");
        FakeHttpGateway.when("POST", "accounts.salla.sa/oauth2/token", 200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":1209600}");
        FakeHttpGateway.when("GET", "/admin/v2/products", 200, "{\"data\":[{\"id\":1,\"name\":\"P\",\"sku\":\"A\",\"price\":{\"amount\":100,\"currency\":\"SAR\"},\"quantity\":5}],\"pagination\":{\"totalPages\":1}}");
        FakeHttpGateway.when("GET", "/admin/v2/orders", 200, "{\"data\":[],\"pagination\":{\"totalPages\":1}}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/sync", "{\"what\":\"products\"}").andExpect(status().isOk());
        drain();
        assertThat(FakeHttpGateway.calls("POST", "oauth2/token").get(0).body()).contains("grant_type=refresh_token").contains("old-refresh").contains("salla-test-id");
        assertThat(FakeHttpGateway.calls("GET", "/admin/v2/products").get(1).headers().get("Authorization")).isEqualTo("Bearer new-access");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + acc, null).andExpect(jsonPath("$.meta.total").value(1));
        // the next call already uses the saved new token
        FakeHttpGateway.CALLS.clear();
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/test", "{}").andExpect(status().isOk());
    }

    @Test
    void anAccountCanBeDisconnectedAndAnEmployeeWithoutTheScreenCannotSeeChannels() throws Exception {
        Shop s = shop();
        String acc = shopify(s);
        onHost(s.t().host(), s.t().access(), "DELETE", "/api/v1/store/integrations/accounts/" + acc, null).andExpect(status().isOk());
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/accounts", null).andExpect(jsonPath("$.length()").value(0));
        onHost(s.t().host(), null, "GET", "/api/v1/store/integrations/accounts", null).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_logs WHERE action = 'INTEGRATION_DISCONNECTED' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.t().slug()).query(Long.class).single()).isEqualTo(1);
    }
}
