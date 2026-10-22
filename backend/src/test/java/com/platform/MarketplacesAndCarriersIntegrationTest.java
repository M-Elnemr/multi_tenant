package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.platform.integrations.SyncService;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** noon and Amazon (price/stock/orders), and shipping with Aramex and Bosta. The platforms are faked. */
class MarketplacesAndCarriersIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired SyncService sync;
    static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void clean() { FakeHttpGateway.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String productId, String variantId, String sku) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String sku = "SKU-" + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","variants":[{"sku":"%s","priceMinor":20000,"barcode":"6221234567890","stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(uniq(), sku, branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        return new Shop(t, JsonPath.read(p, "$.id"), JsonPath.read(p, "$.variants[0].id"), sku);
    }

    void drain() { while (sync.runOne()) { /* every queued job */ } }

    String json(Object o) throws Exception { return JSON.writeValueAsString(o); }

    String pem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(g.generateKeyPair().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
    }

    // ---- noon ----------------------------------------------------------------------------------------------------

    String noon(Shop s) throws Exception {
        FakeHttpGateway.when("POST", "/identity/public/v1/api/login", 200, "{}", Map.of("Set-Cookie", List.of("sid=abc123; Path=/; HttpOnly", "csrf=zzz; Path=/")));
        FakeHttpGateway.when("GET", "/identity/v1/whoami", 200, "{\"id\":\"u1\"}");
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", json(Map.of("provider", "NOON", "country", "EG", "credentials",
                Map.of("keyId", "key-" + uniq(), "privateKey", pem(), "projectCode", "PRJ" + uniq(), "warehouseCode", "WH-CAI-01")))).andExpect(status().isCreated()).andExpect(jsonPath("$.provider").value("NOON")));
        assertThat(res).doesNotContain("PRIVATE KEY");
        String login = FakeHttpGateway.calls("POST", "/identity/public/v1/api/login").get(0).body();
        assertThat((String) JsonPath.read(login, "$.token")).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");   // a signed JWT
        return JsonPath.read(res, "$.id");
    }

    @Test
    void noonPricesAndStockAreSetBySkuAndEachItemStatusIsChecked() throws Exception {
        Shop s = shop();
        String acc = noon(s);
        String plan = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/publish-plan?productId=" + s.productId(), null));
        assertThat((Boolean) JsonPath.read(plan, "$.accounts[0].canCreate")).isFalse();   // noon cannot create products: they are linked by SKU

        FakeHttpGateway.when("POST", "/pricing/v1/pricing/upsert", 200, "{\"items\":[{\"partner_sku\":\"" + s.sku() + "\",\"country_code\":\"eg\",\"status\":{\"status_code\":\"OK\"}}]}");
        FakeHttpGateway.when("POST", "/stock/v1/stock-update", 200, "{\"items\":[{\"partner_sku\":\"" + s.sku() + "\",\"status\":{\"status_code\":\"OK\"}}]}");
        String body = "{\"productId\":\"%s\",\"targets\":[{\"accountId\":\"%s\",\"variants\":[{\"variantId\":\"%s\",\"priceMinor\":23000,\"compareAtMinor\":30000,\"quantity\":4}]}]}".formatted(s.productId(), acc, s.variantId());
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/publish", body).andExpect(status().isOk()));
        assertThat((List<Boolean>) JsonPath.read(res, "$[*].ok")).containsOnly(true);
        var price = FakeHttpGateway.calls("POST", "/pricing/v1/pricing/upsert").get(0);
        assertThat(price.body()).contains("\"partner_sku\":\"" + s.sku() + "\"").contains("\"country_code\":\"eg\"").contains("\"price\":230.0").contains("\"msrp\":300.0");
        assertThat(price.headers().get("Cookie")).contains("sid=abc123").contains("csrf=zzz");
        assertThat(FakeHttpGateway.calls("POST", "/stock/v1/stock-update").get(0).body()).contains("\"warehouse_code\":\"WH-CAI-01\"").contains("\"qty\":4");

        // noon says one SKU is not on noon: the owner sees why
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/identity/public/v1/api/login", 200, "{}", Map.of("Set-Cookie", List.of("sid=abc123; Path=/")));
        FakeHttpGateway.when("POST", "/pricing/v1/pricing/upsert", 200, "{\"items\":[{\"partner_sku\":\"" + s.sku() + "\",\"status\":{\"status_code\":\"NOT_FOUND\",\"message\":\"SKU does not exist\"}}]}");
        String listingId = jdbc.sql("SELECT id::text FROM integrations.listings WHERE account_id = CAST(:a AS uuid)").param("a", acc).query(String.class).single();
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/integrations/listings/" + listingId, "{\"priceMinor\":25000}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SKU does not exist")));
    }

    @Test
    void noonOrdersOfAPartnerFulfilledWarehouseAreImported() throws Exception {
        Shop s = shop();
        String acc = noon(s);
        FakeHttpGateway.when("POST", "/fbpi/v1/fbpi-orders/list", 200, """
                {"orders":[{"fbpi_order_nr":"F1","mp_order_nr":"N-100","currency_code":"EGP","order_created_at":"2026-10-20T08:00:00Z","customer_country_code":"eg",
                  "items":[{"partner_sku":"A","mp_status":"MP_ITEM_STATUS_CONFIRMED","integration_status":"INTEGRATION_ITEM_STATUS_SHIPPED","delivered_invoice_price":120.5},{"partner_sku":"B","mp_status":"MP_ITEM_STATUS_CONFIRMED","integration_status":"INTEGRATION_ITEM_STATUS_UNSPECIFIED","delivered_invoice_price":79.5}]}]}
                """);
        sync.pullOrders(java.util.UUID.fromString(acc), true);
        String list = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?accountId=" + acc, null).andExpect(jsonPath("$.meta.total").value(1)));
        assertThat((String) JsonPath.read(list, "$.data[0].number")).isEqualTo("N-100");
        assertThat((String) JsonPath.read(list, "$.data[0].stage")).isEqualTo("SHIPPED");
        assertThat((Number) JsonPath.read(list, "$.data[0].totalMinor")).isEqualTo(20000);
        assertThat(FakeHttpGateway.calls("POST", "/fbpi/v1/fbpi-orders/list").get(0).body()).contains("\"warehouse_code\":\"WH-CAI-01\"");
        // a seller without partner-fulfilled orders gets a clear message instead of a crash
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "/identity/public/v1/api/login", 200, "{}", Map.of("Set-Cookie", List.of("sid=abc123; Path=/")));
        FakeHttpGateway.when("POST", "/fbpi/v1/fbpi-orders/list", 403, "{\"message\":\"forbidden\"}");
        org.junit.jupiter.api.Assertions.assertThrows(com.platform.integrations.HttpGateway.ApiException.class, () -> sync.pullOrders(java.util.UUID.fromString(acc), true));
    }

    // ---- amazon ----------------------------------------------------------------------------------------------------

    String amazon(Shop s, String refresh) throws Exception {
        FakeHttpGateway.when("POST", "api.amazon.com/auth/o2/token", 200, "{\"access_token\":\"Atza|tok\",\"expires_in\":3600}");
        FakeHttpGateway.when("GET", "/sellers/v1/marketplaceParticipations", 200, "{\"payload\":[{\"marketplace\":{\"id\":\"ARBP9OOSHTCHU\",\"countryCode\":\"EG\",\"name\":\"Amazon.eg\",\"defaultCurrencyCode\":\"EGP\"},\"participation\":{\"isParticipating\":true}}]}");
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", json(Map.of("provider", "AMAZON", "country", "EG", "credentials",
                Map.of("sellerId", "A1SELLER", "refreshToken", refresh, "lwaClientId", "amzn1.client", "lwaClientSecret", "secret"))), "").andExpect(status().isCreated()).andExpect(jsonPath("$.label").value("Amazon Amazon.eg")));
        return JsonPath.read(res, "$.id");
    }

    @Test
    void amazonListingsAreImportedPublishedWithItsOwnPriceAndOrdersAreShippedWithTracking() throws Exception {
        Shop s = shop();
        String acc = amazon(s, "Atzr|" + uniq());
        // another country's marketplace is not accepted for this seller
        FakeHttpGateway.when("GET", "/listings/2021-08-01/items/A1SELLER", 200, """
                {"items":[{"sku":"%s","summaries":[{"asin":"B0TEST","productType":"SHIRT","itemName":"Amazon Tee","mainImage":{"link":"https://m.media-amazon.com/x.jpg"}}],
                  "offers":[{"price":{"amount":"199.00"}}],"fulfillmentAvailability":[{"fulfillmentChannelCode":"DEFAULT","quantity":6}]}],"pagination":{}}
                """.formatted(s.sku()));
        FakeHttpGateway.when("GET", "/orders/v0/orders?", 200, """
                {"payload":{"Orders":[{"AmazonOrderId":"123-456","PurchaseDate":"2026-10-20T10:00:00Z","OrderStatus":"Unshipped","OrderTotal":{"CurrencyCode":"EGP","Amount":"398.00"},"ShippingAddress":{"Name":"Mona","City":"Cairo","CountryCode":"EG","Phone":"01012345678"}}]}}
                """);
        FakeHttpGateway.when("GET", "/orders/123-456/orderItems", 200, "{\"payload\":{\"OrderItems\":[{\"OrderItemId\":\"I1\",\"SellerSKU\":\"" + s.sku() + "\",\"Title\":\"Amazon Tee\",\"QuantityOrdered\":2,\"ItemPrice\":{\"Amount\":\"398.00\"}}]}}");
        drain();
        assertThat(FakeHttpGateway.calls("GET", "/listings/2021-08-01/items/A1SELLER").get(0).url()).contains("marketplaceIds=ARBP9OOSHTCHU");
        assertThat(FakeHttpGateway.calls("GET", "/sellers/v1").get(0).headers().get("x-amz-access-token")).isEqualTo("Atza|tok");
        String list = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/listings?accountId=" + acc, null).andExpect(jsonPath("$.meta.total").value(1)));
        assertThat((Number) JsonPath.read(list, "$.data[0].priceMinor")).isEqualTo(19900);
        assertThat((Number) JsonPath.read(list, "$.data[0].quantity")).isEqualTo(6);

        // change this channel's price and stock: a patch of the listing's offer, with its product type
        FakeHttpGateway.when("PATCH", "/listings/2021-08-01/items/A1SELLER/" + s.sku(), 200, "{\"status\":\"ACCEPTED\"}");
        String listingId = JsonPath.read(list, "$.data[0].id");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/integrations/listings/" + listingId, "{\"priceMinor\":21000,\"quantity\":3}").andExpect(status().isOk());
        String patch = FakeHttpGateway.calls("PATCH", "/listings/2021-08-01/items/A1SELLER/").get(0).body();
        assertThat(patch).contains("\"productType\":\"SHIRT\"").contains("purchasable_offer").contains("210.0").contains("fulfillment_availability").contains("\"quantity\":3");
        // Amazon refusing is reported
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "api.amazon.com/auth/o2/token", 200, "{\"access_token\":\"Atza|tok\",\"expires_in\":3600}");
        FakeHttpGateway.when("PATCH", "/listings/2021-08-01/items/", 200, "{\"status\":\"INVALID\",\"issues\":[{\"message\":\"Price is below the minimum\"}]}");
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/integrations/listings/" + listingId, "{\"priceMinor\":1}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("below the minimum")));

        // the order, and confirming its shipment with a tracking number
        String orders = ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?accountId=" + acc, null).andExpect(jsonPath("$.meta.total").value(1)));
        String orderId = JsonPath.read(orders, "$.data[0].id");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders/" + orderId, null).andExpect(jsonPath("$.actions[0].key").value("ship")).andExpect(jsonPath("$.items[0].quantity").value(2));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/orders/" + orderId + "/actions", "{\"action\":\"ship\",\"params\":{}}").andExpect(status().isUnprocessableEntity());   // Amazon needs a tracking number
        FakeHttpGateway.when("GET", "/orders/123-456/orderItems", 200, "{\"payload\":{\"OrderItems\":[{\"OrderItemId\":\"I1\",\"QuantityOrdered\":2}]}}");
        FakeHttpGateway.when("POST", "/orders/v0/orders/123-456/shipmentConfirmation", 204, "");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/orders/" + orderId + "/actions", "{\"action\":\"ship\",\"params\":{\"trackingNumber\":\"TRK-9\",\"carrier\":\"Aramex\"}}").andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("SHIPPED"));
        String conf = FakeHttpGateway.calls("POST", "shipmentConfirmation").get(0).body();
        assertThat(conf).contains("ARBP9OOSHTCHU").contains("TRK-9").contains("\"orderItemId\":\"I1\"");
    }

    @Test
    void amazonPublishCreatesTheListingWithTheChosenPriceAndRejectsAWrongMarketplace() throws Exception {
        Shop s = shop();
        String acc = amazon(s, "Atzr|" + uniq());
        FakeHttpGateway.when("PUT", "/listings/2021-08-01/items/A1SELLER/" + s.sku(), 200, "{\"status\":\"ACCEPTED\"}");
        String body = "{\"productId\":\"%s\",\"targets\":[{\"accountId\":\"%s\",\"variants\":[{\"variantId\":\"%s\",\"priceMinor\":23000,\"quantity\":5}]}]}".formatted(s.productId(), acc, s.variantId());
        assertThat((List<Boolean>) JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/publish", body)), "$[*].ok")).containsOnly(true);
        String put = FakeHttpGateway.calls("PUT", "/listings/2021-08-01/items/").get(0).body();
        assertThat(put).contains("LISTING_OFFER_ONLY").contains("6221234567890").contains("230.0").contains("\"quantity\":5");   // a barcode matches Amazon's catalogue
        // a seller that does not sell in this marketplace cannot connect
        FakeHttpGateway.reset();
        FakeHttpGateway.when("POST", "api.amazon.com/auth/o2/token", 200, "{\"access_token\":\"Atza|other\",\"expires_in\":3600}");
        FakeHttpGateway.when("GET", "/sellers/v1/marketplaceParticipations", 200, "{\"payload\":[{\"marketplace\":{\"id\":\"A2VIGQ35RCS4UG\",\"name\":\"Amazon.ae\"},\"participation\":{}}]}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", json(Map.of("provider", "AMAZON", "country", "EG", "credentials", Map.of("sellerId", "A2", "refreshToken", "Atzr|" + uniq(), "lwaClientId", "c", "lwaClientSecret", "s"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- carriers --------------------------------------------------------------------------------------------------

    @Test
    void carriersAreListedPerCountry() throws Exception {
        Tenant t = onboard("STORE");
        List<String> eg = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping/carriers/catalog?country=EG", null).andExpect(status().isOk())), "$[*].id");
        assertThat(eg).contains("ARAMEX", "BOSTA", "MYLERZ", "JT", "TURBO").doesNotContain("SMSA", "NAQEL");
        List<String> sa = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping/carriers/catalog?country=SA", null)), "$[*].id");
        assertThat(sa).contains("ARAMEX", "SMSA", "NAQEL", "SPL").doesNotContain("BOSTA");
        List<String> ae = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping/carriers/catalog?country=AE", null)), "$[*].id");
        assertThat(ae).contains("ARAMEX", "EMIRATES_POST", "QUIQUP", "SHIPA").doesNotContain("MYLERZ");
        // the carriers screen never offers marketplaces
        assertThat(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/shipping/carriers/catalog", null))).doesNotContain("AMAZON").doesNotContain("SHOPIFY");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping/carriers", "{\"provider\":\"SHOPIFY\",\"country\":\"EG\",\"credentials\":{}}").andExpect(status().isBadRequest());
    }

    String aramex(Shop s) throws Exception {
        FakeHttpGateway.when("POST", "/Location/Service_1_0.svc/json/FetchCountry", 200, "{\"HasErrors\":false,\"Notifications\":[]}");
        return JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/carriers", json(Map.of("provider", "ARAMEX", "country", "EG", "credentials",
                Map.of("userName", "a@b.com", "password", "pw", "accountNumber", "123456", "accountPin", "654321", "accountEntity", "CAI", "accountCountryCode", "EG", "sandbox", "yes")))).andExpect(status().isCreated())), "$.id");
    }

    String placeOrder(Shop s, String phone) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"FIXED\",\"name\":\"Delivery\",\"feeMinor\":3000}").andExpect(status().isCreated());
        String method = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/shipping-methods", null)), "$[0].id");
        String order = ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona","phone":"%s","addressLine1":"1 Tahrir St","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variantId(), method, phone), "k-" + uniq()).andExpect(status().isCreated()));
        return JsonPath.read(order, "$.orderId");
    }

    @Test
    void aShopOrderIsShippedWithAramexTrackedAndMovedToArrived() throws Exception {
        Shop s = shop();
        FakeHttpGateway.when("POST", "/Location/Service_1_0.svc/json/FetchCountry", 200, "{\"HasErrors\":true,\"Notifications\":[{\"Code\":\"ERR01\",\"Message\":\"Invalid credentials\"}]}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/carriers", json(Map.of("provider", "ARAMEX", "country", "EG", "credentials", Map.of("userName", "x", "password", "y", "accountNumber", "1", "accountPin", "2", "accountEntity", "CAI", "accountCountryCode", "EG"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid credentials")));
        FakeHttpGateway.reset();
        String acc = aramex(s);
        String order = placeOrder(s, "01012345678");
        String ship = "{\"orderId\":\"%s\",\"accountId\":\"%s\",\"weightKg\":1.5,\"pieces\":1,\"description\":\"Tee\"}".formatted(order, acc);

        // the pickup address comes from the shop's branch: refused until it is filled in
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments", ship).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SENDER_INCOMPLETE"));
        jdbc.sql("UPDATE commerce.branches SET phone = '+201000000001', address_line1 = '5 Nile St', city = 'Cairo' WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.t().slug()).update();

        FakeHttpGateway.when("POST", "/Shipping/Service_1_0.svc/json/CreateShipments", 200, "{\"HasErrors\":false,\"Shipments\":[{\"ID\":\"AWB123\",\"HasErrors\":false,\"ShipmentLabel\":{\"LabelURL\":\"https://aramex.test/label.pdf\"}}]}");
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments", ship).andExpect(status().isCreated()).andExpect(jsonPath("$.trackingNumber").value("AWB123")).andExpect(jsonPath("$.labelUrl").value("https://aramex.test/label.pdf")).andExpect(jsonPath("$.carrier").value("Aramex")));
        String req = FakeHttpGateway.calls("POST", "CreateShipments").get(0).body();
        assertThat(req).contains("\"ProductGroup\":\"DOM\"").contains("\"Services\":\"CODS\"").contains("\"Value\":230.0").contains("\"City\":\"Cairo\"").contains("5 Nile St").contains("1 Tahrir St").contains("\"CountryCode\":\"EG\"");   // cash on delivery = order total
        assertThat(FakeHttpGateway.calls("POST", "CreateShipments").get(0).url()).startsWith("https://ws.dev.aramex.net");   // the sandbox flag

        // the order carries the tracking number and moved to SHIPPED (through PREPARING)
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/orders/" + order, null).andExpect(jsonPath("$.status").value("SHIPPED")).andExpect(jsonPath("$.trackingNumber").value("AWB123")).andExpect(jsonPath("$.courierName").value("Aramex"));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/shipping/shipments?orderId=" + order, null).andExpect(jsonPath("$.meta.total").value(1));

        // the carrier delivers: following the parcel moves the order to ARRIVED
        String shipmentId = JsonPath.read(res, "$.id");
        FakeHttpGateway.when("POST", "/Tracking/Service_1_0.svc/json/TrackShipments", 200, "{\"TrackingResults\":[{\"Key\":\"AWB123\",\"Value\":[{\"UpdateCode\":\"SH005\",\"UpdateDescription\":\"Delivered\",\"UpdateLocation\":\"Cairo\",\"UpdateDateTime\":\"/Date(1761000000000)/\"}]}]}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments/" + shipmentId + "/refresh", "{}").andExpect(jsonPath("$.status").value("DELIVERED")).andExpect(jsonPath("$.lastEvent").value(org.hamcrest.Matchers.containsString("Delivered")));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/orders/" + order, null).andExpect(jsonPath("$.status").value("ARRIVED"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments/" + shipmentId + "/cancel", "{}").andExpect(status().isConflict());   // delivered parcels cannot be cancelled

        // a different shop sees none of it
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/shipping/shipments", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/shipping/shipments", ship).andExpect(status().isNotFound());
    }

    @Test
    void aCarrierRefusalIsReportedAndLeavesTheOrderAlone() throws Exception {
        Shop s = shop();
        String acc = aramex(s);
        String order = placeOrder(s, "01022223333");
        jdbc.sql("UPDATE commerce.branches SET phone = '+201000000001', address_line1 = '5 Nile St', city = 'Cairo' WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.t().slug()).update();
        FakeHttpGateway.when("POST", "/Shipping/Service_1_0.svc/json/CreateShipments", 200, "{\"HasErrors\":true,\"Notifications\":[{\"Code\":\"ERR\",\"Message\":\"Consignee phone is invalid\"}],\"Shipments\":[]}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments", "{\"orderId\":\"%s\",\"accountId\":\"%s\"}".formatted(order, acc)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARRIER_REJECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("phone is invalid")));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/orders/" + order, null).andExpect(jsonPath("$.status").value("REQUESTED"));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/shipping/shipments?orderId=" + order, null).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void anOrderFromAChannelCanBeShippedWithBostaAndTheChannelIsToldWithTheTracking() throws Exception {
        Shop s = shop();
        // a WooCommerce store with one order
        FakeHttpGateway.when("GET", "/wc/v3/products?per_page=1", 200, "[]");
        FakeHttpGateway.when("GET", "/data/currencies/current", 200, "{\"code\":\"EGP\"}");
        String woo = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", json(Map.of("provider", "WOOCOMMERCE", "country", "EG", "credentials",
                Map.of("siteUrl", "https://example.com", "consumerKey", "ck", "consumerSecret", "cs")))).andExpect(status().isCreated())), "$.id");
        FakeHttpGateway.when("GET", "/wc/v3/products?per_page=50", 200, "[]");
        FakeHttpGateway.when("GET", "/wc/v3/orders", 200, """
                [{"id":801,"number":"801","status":"processing","total":"350.00","currency":"EGP","date_created_gmt":"2026-10-21T09:00:00",
                  "billing":{"first_name":"Sara","last_name":"K","phone":"01099998888","email":"s@k.com","address_1":"12 Abbas St","city":"Nasr City","country":"EG"},"shipping":{"address_1":"12 Abbas St","city":"Cairo","country":"EG"},
                  "line_items":[{"name":"Tee","sku":"T1","quantity":1,"price":"350.00","total":"350.00"}]}]
                """);
        drain();
        jdbc.sql("UPDATE commerce.branches SET phone = '+201000000001', address_line1 = '5 Nile St', city = 'Cairo' WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.t().slug()).update();
        String orderId = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?accountId=" + woo, null).andExpect(jsonPath("$.meta.total").value(1))), "$.data[0].id");

        FakeHttpGateway.when("GET", "/pickup-locations", 200, "[]");
        String bosta = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/carriers", json(Map.of("provider", "BOSTA", "country", "EG", "credentials", Map.of("apiKey", "bosta-key-" + uniq()))))
                .andExpect(status().isCreated())), "$.id");
        FakeHttpGateway.when("POST", "/api/v2/deliveries", 201, "{\"_id\":\"d1\",\"trackingNumber\":\"BO-7788\"}");
        FakeHttpGateway.when("PUT", "/wc/v3/orders/801", 200, "{}");
        FakeHttpGateway.when("POST", "/wc/v3/orders/801/notes", 201, "{}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments", "{\"channelOrderId\":\"%s\",\"accountId\":\"%s\",\"codMinor\":35000,\"weightKg\":2}".formatted(orderId, bosta)).andExpect(status().isCreated()).andExpect(jsonPath("$.trackingNumber").value("BO-7788"));
        var create = FakeHttpGateway.calls("POST", "/api/v2/deliveries").get(0);
        assertThat(create.headers().get("Authorization")).startsWith("bosta-key-");
        assertThat(create.body()).contains("\"cod\":350.0").contains("\"firstName\":\"Sara\"").contains("\"phone\":\"01099998888\"").contains("12 Abbas St");
        assertThat(FakeHttpGateway.calls("PUT", "/wc/v3/orders/801").get(0).body()).contains("completed");   // the store is told it shipped
        assertThat(FakeHttpGateway.calls("POST", "/wc/v3/orders/801/notes").get(0).body()).contains("BO-7788");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders/" + orderId, null).andExpect(jsonPath("$.trackingNumber").value("BO-7788")).andExpect(jsonPath("$.shipments.length()").value(1));
    }

    @Test
    void aShopOrderIsShippedWithAymakanAndTrackedToDelivered() throws Exception {
        Shop s = shop();
        FakeHttpGateway.when("GET", "/v2/cities", 401, "{\"error\":true,\"response\":\"Invalid Credentials\"}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/carriers", json(Map.of("provider", "AYMAKAN", "country", "SA", "credentials", Map.of("apiKey", "bad")))).andExpect(status().isUnprocessableEntity());
        FakeHttpGateway.reset();
        FakeHttpGateway.when("GET", "/v2/cities", 200, "{\"success\":true,\"data\":{\"cities\":[]}}");
        String acc = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/carriers", json(Map.of("provider", "AYMAKAN", "country", "SA", "credentials", Map.of("apiKey", "aym-key-" + uniq(), "sandbox", "yes")))).andExpect(status().isCreated())), "$.id");
        String order = placeOrder(s, "01012345678");
        jdbc.sql("UPDATE commerce.branches SET phone = '+966500000001', address_line1 = '5 King Rd', city = 'Riyadh' WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.t().slug()).update();
        FakeHttpGateway.when("POST", "/v2/shipping/create", 200, "{\"success\":true,\"shipping\":{\"tracking_number\":\"AY123\",\"pdf_label\":\"https://aymakan.test/label.pdf\"}}");
        String res = ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments", "{\"orderId\":\"%s\",\"accountId\":\"%s\",\"weightKg\":1,\"pieces\":1}".formatted(order, acc))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.trackingNumber").value("AY123")).andExpect(jsonPath("$.labelUrl").value("https://aymakan.test/label.pdf")));
        var create = FakeHttpGateway.calls("POST", "/v2/shipping/create").get(0);
        assertThat(create.url()).startsWith("https://dev-api.aymakan.com.sa");
        assertThat(create.headers().get("Authorization")).startsWith("aym-key-");
        assertThat(create.body()).contains("\"is_cod\":1").contains("\"cod_amount\":230.0").contains("\"delivery_name\":\"Mona\"").contains("\"collection_city\":\"Riyadh\"");
        FakeHttpGateway.when("GET", "/v2/shipping/track/AY123", 200, "{\"success\":true,\"data\":{\"shipments\":[{\"status\":\"Delivered\",\"tracking_info\":[{\"description\":\"Shipment delivered\",\"created_at\":\"2026-10-22 10:00:00\"}]}]}}");
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/shipping/shipments/" + JsonPath.read(res, "$.id") + "/refresh", "{}").andExpect(jsonPath("$.status").value("DELIVERED"));
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/orders/" + order, null).andExpect(jsonPath("$.status").value("ARRIVED"));
    }

    @Test
    void zidProductsAndOrdersAreReadAndPriceIsPushed() throws Exception {
        Shop s = shop();
        FakeHttpGateway.when("GET", "/managers/account/profile", 200, "{\"user\":{\"id\":7,\"store\":{\"id\":\"st-1\",\"title\":\"Zid Shop\",\"currency\":\"SAR\"}}}");
        FakeHttpGateway.when("GET", "/v1/products/", 200, "{\"next\":null,\"results\":[{\"id\":\"p1\",\"sku\":\"" + s.sku() + "\",\"name\":\"Tee\",\"price\":200,\"sale_price\":null,\"quantity\":4,\"is_published\":true,\"images\":[],\"variants\":[]}]}");
        FakeHttpGateway.when("GET", "/managers/store/orders", 200, "{\"orders\":[{\"id\":55,\"code\":\"Z-55\",\"order_status\":\"new\",\"order_total\":\"150.00\",\"currency_code\":\"SAR\",\"created_at\":\"2026-10-21T09:00:00Z\","
                + "\"customer\":{\"name\":\"Ali\",\"mobile\":\"0501112222\"},\"shipping\":{\"address\":{\"street\":\"King Rd\",\"city\":{\"name\":\"Riyadh\"}}},\"products\":[{\"sku\":\"" + s.sku() + "\",\"name\":\"Tee\",\"quantity\":1,\"price\":\"150.00\",\"total\":\"150.00\"}]}],\"total_order_count\":1}");
        String acc = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/integrations/accounts", json(Map.of("provider", "ZID", "country", "SA", "credentials", Map.of("authorization", "auth-tok", "managerToken", "mgr-tok"))))
                .andExpect(status().isCreated())), "$.id");
        drain();
        var call = FakeHttpGateway.calls("GET", "/v1/products/").get(0);
        assertThat(call.headers().get("Authorization")).isEqualTo("Bearer auth-tok");
        assertThat(call.headers().get("X-Manager-Token")).isEqualTo("mgr-tok");
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/integrations/orders?accountId=" + acc, null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].customerName").value("Ali")).andExpect(jsonPath("$.data[0].totalMinor").value(15000));
    }
}
