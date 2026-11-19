package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.integrations.SyncService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Facebook & Instagram Shop (Meta product catalog): found by the same one-click Facebook login as the Page, products sent with the shop's own price and stock,
 * a catalog item Meta rejects is shown to the owner, and a channel set to follow the shop sends new and edited products by itself. Meta is faked.
 */
class MetaCatalogIntegrationTest extends IntegrationTestBase {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String CATALOG = "770011223";
    @Autowired JdbcClient jdbc;
    @Autowired SyncService sync;

    @BeforeEach
    void clean() {
        FakeMetaGraph.reset();
        FakeMetaGraph.CATALOGS = true;
        FakeMetaGraph.PAGE_ID = "5" + uniq().replaceAll("[^0-9]", "7") + "1111";
        FakeMetaGraph.IG_ID = "6" + uniq().replaceAll("[^0-9]", "8") + "2222";
        FakeHttpGateway.reset();
        acceptCatalog();
    }

    /** What Meta answers for a healthy catalog: it exists, has no products yet, accepts a batch and finds no error in it. */
    void acceptCatalog() {
        FakeHttpGateway.when("GET", "/" + CATALOG + "?fields=id,name,product_count", 200, "{\"id\":\"" + CATALOG + "\",\"name\":\"Main catalog\"}");
        FakeHttpGateway.when("GET", "/" + CATALOG + "/products?", 200, "{\"data\":[]}");
        FakeHttpGateway.when("POST", "/" + CATALOG + "/items_batch", 200, "{\"handles\":[\"h1\"]}");
        FakeHttpGateway.when("GET", "check_batch_request_status", 200, "{\"data\":[{\"status\":\"finished\",\"errors_total_count\":0}]}");
    }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String loginState(Tenant t) throws Exception {
        String url = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/meta/start", null).andExpect(status().isOk())), "$.url");
        assertThat(url).contains("catalog_management");   // the same login now also asks for the catalog permission
        String raw = url.substring(url.indexOf("state=") + 6);
        return URLDecoder.decode(raw.substring(0, raw.indexOf('&')), StandardCharsets.UTF_8);
    }

    /** One click on "Connect with Facebook", then the owner ticks what to connect. Returns the connect answer. */
    String connect(Tenant t, String... keys) throws Exception {
        String location = mvc.perform(get("/api/v1/chat/meta/callback").header("Host", "platform.test").param("code", "goodcode").param("state", loginState(t)))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String id = location.substring(location.indexOf("connect=") + 8);
        String found = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/meta/pending/" + id, null).andExpect(status().isOk()));
        assertThat(found).contains("Main catalog").contains("NEW_CATALOG").doesNotContain("TOKEN");
        return ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/meta/pending/" + id + "/connect", JSON.writeValueAsString(java.util.Map.of("keys", List.of(keys)))).andExpect(status().isOk()));
    }

    record Shop(Tenant t, String productId, String variantId, String sku) {}

    Shop product(Tenant t, String name, long priceMinor, int stock, boolean withPicture) throws Exception {
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String sku = "SKU-" + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"Soft cotton tee","variants":[{"sku":"%s","priceMinor":%d,"stock":[{"branchId":"%s","quantity":%d}]}]}
                """.formatted(name, sku, priceMinor, branch, stock);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String id = JsonPath.read(p, "$.id");
        if (withPicture) onHost(t.host(), t.access(), "POST", "/api/v1/store/products/" + id + "/media", "{\"url\":\"https://img.test/" + sku + ".jpg\"}").andExpect(status().isOk());
        return new Shop(t, id, JsonPath.read(p, "$.variants[0].id"), sku);
    }

    String account(Tenant t) throws Exception {
        List<String> ids = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/accounts?kind=STORE_PLATFORM", null)), "$[?(@.provider=='META_CATALOG')].id");
        return ids.get(0);
    }

    JsonNode lastBatch() throws Exception {
        var calls = FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch");
        assertThat(calls).isNotEmpty();
        return JSON.readTree(calls.get(calls.size() - 1).body());
    }

    String publish(Shop s, String account, String variantJson) throws Exception {
        String body = "{\"productId\":\"%s\",\"targets\":[{\"accountId\":\"%s\",\"variants\":[%s]}]}".formatted(s.productId(), account, variantJson);
        return ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/publish", body).andExpect(status().isOk()));
    }

    /** The queue waits a short while after the last edit; the tests do not. */
    void runDue() {
        jdbc.sql("UPDATE integrations.auto_queue SET queued_at = now() - interval '10 minutes'").update();
        jdbc.sql("UPDATE integrations.jobs SET next_run_at = now() WHERE status = 'PENDING'").update();
        while (sync.runOne()) { /* every due job */ }
    }

    @Test
    void oneFacebookLoginFindsTheCatalogAndAShopWithoutOneCanHaveItMade() throws Exception {
        Tenant t = onboard("STORE");
        String res = connect(t, "CATALOG:" + CATALOG);
        assertThat(res).contains("META_CATALOG").doesNotContain("TOKEN");
        var accounts = ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/accounts", null));
        assertThat((List<String>) JsonPath.read(accounts, "$[?(@.provider=='META_CATALOG')].label")).containsExactly("Main catalog");
        // the owner is told it is one of the places a product can go
        onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/catalog", null).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='META_CATALOG')].oauth").value(true));

        Tenant fresh = onboard("STORE");   // choosing "create a new catalog": it is made on Meta, named after the shop, and then connected
        FakeHttpGateway.when("GET", "/880099001?fields=id,name,product_count", 200, "{\"id\":\"880099001\",\"name\":\"Shop - elmanassa\"}");
        FakeHttpGateway.when("GET", "/880099001/products?", 200, "{\"data\":[]}");
        connect(fresh, "CATALOG:new:990011", "FACEBOOK:" + FakeMetaGraph.PAGE_ID);
        assertThat(FakeMetaGraph.SENT).extracting(FakeMetaGraph.Sent::path).contains("990011/owned_product_catalogs");
        assertThat(ok(onHost(fresh.host(), fresh.access(), "GET", "/api/v1/store/integrations/accounts", null))).contains("880099001");
        // and, because the Page was chosen too, the catalog was attached to the Page for its Shop tab
        assertThat(FakeMetaGraph.SENT).extracting(FakeMetaGraph.Sent::path).contains(FakeMetaGraph.PAGE_ID + "/product_catalogs");
    }

    @Test
    void aProductIsSentWithTheShopsPriceStockLinkAndPictureAndASalePriceIsKeptApart() throws Exception {
        Tenant t = onboard("STORE");
        connect(t, "CATALOG:" + CATALOG);
        String acc = account(t);
        Shop noPicture = product(t, "Tee " + uniq(), 20000, 9, false);
        String refused = publish(noPicture, acc, "{\"variantId\":\"" + noPicture.variantId() + "\"}");
        assertThat((Boolean) JsonPath.read(refused, "$[0].ok")).isFalse();
        assertThat((String) JsonPath.read(refused, "$[0].error")).contains("picture");   // told why, in words
        assertThat(FakeHttpGateway.calls("POST", "/items_batch")).isEmpty();

        Shop s = product(t, "Tee " + uniq(), 20000, 9, true);
        assertThat((Boolean) JsonPath.read(publish(s, acc, "{\"variantId\":\"" + s.variantId() + "\"}"), "$[0].ok")).isTrue();
        var call = FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch").get(0);
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer LONG-USER-TOKEN-0123456789");
        JsonNode data = lastBatch().path("requests").get(0).path("data");
        assertThat(lastBatch().path("item_type").asText()).isEqualTo("PRODUCT_ITEM");
        assertThat(data.path("id").asText()).isEqualTo(s.sku());   // the shop's SKU is the catalog's own id for the item
        assertThat(data.path("price").asText()).isEqualTo("200.00 EGP");
        assertThat(data.path("availability").asText()).isEqualTo("in stock");
        assertThat(data.path("quantity_to_sell_on_facebook").asInt()).isEqualTo(9);
        assertThat(data.path("link").asText()).startsWith("http").contains("/products/");
        assertThat(data.path("image_link").asText()).isEqualTo("https://img.test/" + s.sku() + ".jpg");
        assertThat(data.has("sale_price")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.listings WHERE variant_id = CAST(:v AS uuid)").param("v", s.variantId()).query(Long.class).single()).isEqualTo(1L);

        // a channel price below the shop's old price is a sale: the catalog shows the old price struck through
        Shop sale = product(t, "Hoodie " + uniq(), 30000, 0, true);
        publish(sale, acc, "{\"variantId\":\"" + sale.variantId() + "\",\"priceMinor\":23000,\"compareAtMinor\":30000,\"quantity\":0}");
        JsonNode d2 = lastBatch().path("requests").get(0).path("data");
        assertThat(d2.path("price").asText()).isEqualTo("300.00 EGP");
        assertThat(d2.path("sale_price").asText()).isEqualTo("230.00 EGP");
        assertThat(d2.path("availability").asText()).isEqualTo("out of stock");   // no stock: never shown as available
    }

    @Test
    void anItemMetaRejectsIsNotShownAsPublishedAndTheOwnerSeesWhy() throws Exception {
        Tenant t = onboard("STORE");
        connect(t, "CATALOG:" + CATALOG);
        String acc = account(t);
        Shop s = product(t, "Tee " + uniq(), 20000, 3, true);
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/" + CATALOG + "/items_batch", 200, "{\"handles\":[\"h2\"]}");
        FakeHttpGateway.when("GET", "check_batch_request_status", 200, "{\"data\":[{\"status\":\"finished\",\"errors_total_count\":1,\"errors\":[{\"message\":\"Invalid image URL\"}]}]}");
        String res = publish(s, acc, "{\"variantId\":\"" + s.variantId() + "\"}");
        assertThat((Boolean) JsonPath.read(res, "$[0].ok")).isFalse();
        assertThat((String) JsonPath.read(res, "$[0].error")).contains("Invalid image URL");
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.listings WHERE variant_id = CAST(:v AS uuid)").param("v", s.variantId()).query(Long.class).single()).isZero();
    }

    @Test
    void aChannelThatFollowsTheShopSendsNewAndEditedProductsByItselfAndStopsWhenSwitchedOff() throws Exception {
        Tenant t = onboard("STORE");
        Tenant other = onboard("STORE");
        connect(t, "CATALOG:" + CATALOG);
        String acc = account(t);
        Shop first = product(t, "Tee " + uniq(), 20000, 5, true);

        // off by default: an edit queues nothing
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/variants/" + first.variantId(), "{\"priceMinor\":21000}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.auto_queue").query(Long.class).single()).isZero();
        // another shop cannot switch it on
        onHost(other.host(), other.access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/auto-publish", "{\"enabled\":true}").andExpect(status().isNotFound());

        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/auto-publish", "{\"enabled\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.autoPublish").value(true));
        assertThat(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/integrations/accounts", null))).contains("\"autoPublish\":true");
        // a new product with its picture goes out by itself, with the shop's current price
        Shop fresh = product(t, "Cap " + uniq(), 15000, 4, true);
        FakeHttpGateway.CALLS.clear();
        runDue();
        var sent = FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch");
        assertThat(sent).isNotEmpty();
        assertThat(sent.stream().map((c) -> c.body()).toList()).anyMatch((b) -> b.contains(fresh.sku()) && b.contains("150.00 EGP"));

        // a price edit and a stock-out reach the catalog; the shop's price is the one shown
        FakeHttpGateway.CALLS.clear();
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/variants/" + fresh.variantId(), "{\"priceMinor\":17500}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.auto_queue WHERE product_id = CAST(:p AS uuid)").param("p", fresh.productId()).query(Long.class).single()).isEqualTo(1L);
        runDue();
        assertThat(FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch").stream().map((c) -> c.body()).toList()).anyMatch((b) -> b.contains(fresh.sku()) && b.contains("175.00 EGP"));
        assertThat(jdbc.sql("SELECT price_minor FROM integrations.listings WHERE variant_id = CAST(:v AS uuid)").param("v", fresh.variantId()).query(Long.class).single()).isEqualTo(17500L);

        // archiving the product sets it to zero stock on the catalog instead of leaving it for sale
        FakeHttpGateway.CALLS.clear();
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/products/" + fresh.productId(), null).andExpect(status().isNoContent());
        runDue();
        assertThat(FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch").stream().map((c) -> c.body()).toList()).anyMatch((b) -> b.contains(fresh.sku()) && b.contains("out of stock"));

        // switched off: nothing more is queued
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/auto-publish", "{\"enabled\":false}").andExpect(status().isOk());
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/variants/" + first.variantId(), "{\"priceMinor\":22000}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.auto_queue").query(Long.class).single()).isZero();
    }

    @Test
    void sendingEveryProductAtOnceQueuesTheShopsActiveProductsAndSendsThemInTheBackground() throws Exception {
        Tenant t = onboard("STORE");
        connect(t, "CATALOG:" + CATALOG);
        String acc = account(t);
        Shop a = product(t, "Tee " + uniq(), 20000, 5, true);
        Shop b = product(t, "Cap " + uniq(), 15000, 5, true);
        onHost(t.host(), t.access(), "POST", "/api/v1/store/integrations/accounts/" + acc + "/publish-all", null).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(2));
        FakeHttpGateway.CALLS.clear();
        runDue();
        String all = String.join(" ", FakeHttpGateway.calls("POST", "/" + CATALOG + "/items_batch").stream().map((c) -> c.body()).toList());
        assertThat(all).contains(a.sku()).contains(b.sku());
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.listings WHERE account_id = CAST(:a AS uuid)").param("a", acc).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM integrations.auto_queue").query(Long.class).single()).isZero();
    }
}
