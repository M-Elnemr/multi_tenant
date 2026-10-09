package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.MockPaymentProvider;
import com.platform.commerce.OrderService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

class CommerceIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired MockPaymentProvider provider;
    @Autowired OrderService orderService;

    record Shop(Tenant tenant, String branchId, String productId, String slug, String variantS, String variantM) {}

    String read(String json, String path) { return JsonPath.read(json, path).toString(); }

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    /** Store with one product "T-Shirt" (Size S/M, 100.00 EGP each) and the given stock per variant. */
    Shop shop(int stockS, int stockM) throws Exception {
        Tenant t = onboard("STORE");
        String branch = read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andExpect(status().isOk())), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"MEN","name":"T-Shirt %5$s","description":"Cotton","options":[{"name":"Size","values":["S","M"]}],
                 "variants":[
                  {"sku":"TS-S-%1$s","priceMinor":10000,"optionValues":{"Size":"S"},"stock":[{"branchId":"%2$s","quantity":%3$d}]},
                  {"sku":"TS-M-%1$s","priceMinor":10000,"optionValues":{"Size":"M"},"stock":[{"branchId":"%2$s","quantity":%4$d}]}]}
                """.formatted(uniq(), branch, stockS, stockM, uniq());
        String res = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        return new Shop(t, branch, read(res, "$.id"), read(res, "$.slug"), read(res, "$.variants[0].id"), read(res, "$.variants[1].id"));
    }

    String customerToken(Shop s) throws Exception { return googleClient(s.tenant().host()); }

    String shippingId(Shop s, String type) throws Exception {
        String res = ok(onHost(s.tenant().host(), null, "GET", "/api/v1/shop/profile", null));
        return JsonPath.<List<String>>read(res, "$.shippingMethods[?(@.type=='" + type + "')].id").get(0);
    }

    String checkoutBody(Shop s, String variant, int qty, String payment, String extra) throws Exception {
        return """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"%s",
                 "address":{"recipientName":"Mona","phone":"01011112222","addressLine1":"1 Tahrir St","city":"Cairo"}%s}
                """.formatted(variant, qty, shippingId(s, "FIXED"), payment, extra);
    }

    int available(Shop s, String variant) {
        return jdbc.sql("SELECT quantity_on_hand - quantity_reserved FROM commerce.inventory_items WHERE variant_id = :v").param("v", UUID.fromString(variant)).query(Integer.class).single();
    }

    int onHand(String variant) {
        return jdbc.sql("SELECT quantity_on_hand FROM commerce.inventory_items WHERE variant_id = :v").param("v", UUID.fromString(variant)).query(Integer.class).single();
    }

    @Test
    void codOrderFullLifecycleWithServerSidePricingAndSnapshots() throws Exception {
        Shop s = shop(5, 5);
        String cust = customerToken(s);
        String h = s.tenant().host();

        onHost(h, null, "GET", "/api/v1/shop/products", null).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].minPriceMinor").value(10000)).andExpect(jsonPath("$.data[0].inStock").value(true));
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slug(), null).andExpect(status().isOk()).andExpect(jsonPath("$.variants.length()").value(2));

        // Client cannot dictate the total: the server prices from the database
        String order = ok(onHost(h, cust, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantS(), 2, "CASH_ON_DELIVERY", ""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.totalMinor").value(25000)));
        String orderId = read(order, "$.orderId");
        assertThat(read(order, "$.orderNumber")).startsWith("ST-");
        assertThat(available(s, s.variantS())).isEqualTo(3);   // reserved 2
        assertThat(onHand(s.variantS())).isEqualTo(5);

        // price change after the sale must not alter the historical order
        onHost(h, s.tenant().access(), "PATCH", "/api/v1/store/variants/" + s.variantS(), "{\"priceMinor\":99999}").andExpect(status().isOk());
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/orders/" + orderId, null)
                .andExpect(jsonPath("$.items[0].unitPriceMinor").value(10000)).andExpect(jsonPath("$.totalMinor").value(25000));

        for (String next : List.of("PREPARING", "SHIPPED", "ARRIVED"))
            onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"" + next + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(next));
        assertThat(onHand(s.variantS())).isEqualTo(3);        // sold: on-hand reduced, reservation consumed
        assertThat(available(s, s.variantS())).isEqualTo(3);
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.paymentStatus").value("PAID")).andExpect(jsonPath("$.history.length()").value(4));
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"PREPARING\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/reports/summary", null).andExpect(status().isOk()).andExpect(jsonPath("$.ordersThisMonth").value(1)).andExpect(jsonPath("$.topProducts[0].units").value(2));
    }

    @Test
    void cardPaymentIsConfirmedOnlyByVerifiedMatchingIdempotentWebhook() throws Exception {
        Shop s = shop(5, 5);
        String cust = customerToken(s);
        String h = s.tenant().host();
        // card is disabled by default for a new store
        onHost(h, cust, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantS(), 1, "CARD", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        onHost(h, s.tenant().access(), "PUT", "/api/v1/store/payment-methods", "{\"method\":\"CARD\",\"enabled\":true}").andExpect(status().isOk());

        String order = ok(onHost(h, cust, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantS(), 1, "CARD", "")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.checkoutUrl").exists()));
        String orderId = read(order, "$.orderId");
        assertThat(available(s, s.variantS())).isEqualTo(4);

        String wrongAmount = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"orderId\":\"%s\",\"amountMinor\":1}".formatted(uniq(), orderId);
        mvc.perform(post("/api/v1/shop/webhooks/mock").header("Host", h).header("X-Signature", "bad").contentType(MediaType.APPLICATION_JSON).content(wrongAmount)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/shop/webhooks/mock").header("Host", h).header("X-Signature", provider.sign(wrongAmount)).contentType(MediaType.APPLICATION_JSON).content(wrongAmount)).andExpect(status().isBadRequest());
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.paymentStatus").value("UNPAID"));

        String good = "{\"eventId\":\"e%s\",\"type\":\"payment.succeeded\",\"orderId\":\"%s\",\"amountMinor\":15000}".formatted(uniq(), orderId);
        for (String expected : new String[] {"PROCESSED", "DUPLICATE"})
            mvc.perform(post("/api/v1/shop/webhooks/mock").header("Host", h).header("X-Signature", provider.sign(good)).contentType(MediaType.APPLICATION_JSON).content(good))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.result").value(expected));
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.paymentStatus").value("PAID"));

        // refunds: linked to the payment, cannot exceed what was paid
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/refund", "{\"amountMinor\":99999}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_PAID"));
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/refund", "{\"amountMinor\":5000}").andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"));
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.order_payments WHERE order_id = :o AND kind = 'REFUND' AND original_payment_id IS NOT NULL").param("o", UUID.fromString(orderId)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void onlyOneBuyerGetsTheLastUnit() throws Exception {
        Shop s = shop(1, 5);
        String c1 = customerToken(s);
        String c2 = customerToken(s);
        String body = checkoutBody(s, s.variantS(), 1, "CASH_ON_DELIVERY", "");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(
                    () -> onHost(s.tenant().host(), c1, "POST", "/api/v1/shop/checkout", body).andReturn().getResponse().getStatus(),
                    () -> onHost(s.tenant().host(), c2, "POST", "/api/v1/shop/checkout", body).andReturn().getResponse().getStatus());
            List<Integer> codes = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(jobs)) codes.add(f.get());
            assertThat(codes).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(available(s, s.variantS())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.tenant().slug()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void cancellingReleasesStockAndShoppersOnlySeeTheirOwnOrders() throws Exception {
        Shop s = shop(3, 3);
        String mine = customerToken(s);
        String other = customerToken(s);
        String h = s.tenant().host();
        String orderId = read(ok(onHost(h, mine, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantM(), 2, "CASH_ON_DELIVERY", "")).andExpect(status().isCreated())), "$.orderId");
        assertThat(available(s, s.variantM())).isEqualTo(1);
        onHost(h, other, "GET", "/api/v1/shop/orders/" + orderId, null).andExpect(status().isNotFound());
        onHost(h, other, "POST", "/api/v1/shop/orders/" + orderId + "/cancel", "{}").andExpect(status().isNotFound());
        onHost(h, mine, "POST", "/api/v1/shop/orders/" + orderId + "/cancel", "{\"reason\":\"changed my mind\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(available(s, s.variantM())).isEqualTo(3);
        onHost(h, mine, "GET", "/api/v1/shop/orders", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, other, "GET", "/api/v1/shop/orders", null).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void tenantIsolationAcrossStores() throws Exception {
        Shop a = shop(2, 2);
        Shop b = shop(2, 2);
        String custA = customerToken(a);
        String orderA = read(ok(onHost(a.tenant().host(), custA, "POST", "/api/v1/shop/checkout", checkoutBody(a, a.variantS(), 1, "CASH_ON_DELIVERY", "")).andExpect(status().isCreated())), "$.orderId");

        // B's owner cannot reach A's records by id (404, never 200/403 that would confirm existence)
        onHost(b.tenant().host(), b.tenant().access(), "GET", "/api/v1/store/products/" + a.productId(), null).andExpect(status().isNotFound());
        onHost(b.tenant().host(), b.tenant().access(), "GET", "/api/v1/store/orders/" + orderA, null).andExpect(status().isNotFound());
        onHost(b.tenant().host(), b.tenant().access(), "POST", "/api/v1/store/orders/" + orderA + "/status", "{\"status\":\"CANCELLED\"}").andExpect(status().isNotFound());
        onHost(b.tenant().host(), b.tenant().access(), "PATCH", "/api/v1/store/variants/" + a.variantS(), "{\"priceMinor\":1}").andExpect(status().isNotFound());
        onHost(b.tenant().host(), b.tenant().access(), "POST", "/api/v1/store/inventory/adjustments",
                "{\"branchId\":\"%s\",\"variantId\":\"%s\",\"delta\":100}".formatted(b.branchId(), a.variantS())).andExpect(status().isNotFound());
        // A's token carries no authority on B's host; shopper token of A cannot order from B
        onHost(b.tenant().host(), a.tenant().access(), "GET", "/api/v1/store/orders", null).andExpect(status().isForbidden());
        onHost(b.tenant().host(), custA, "GET", "/api/v1/shop/orders", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0));   // A's order is not visible in B
        // B's public catalog never shows A's product
        onHost(b.tenant().host(), null, "GET", "/api/v1/shop/products/" + a.slug(), null).andExpect(status().isNotFound());
        // cross-tenant variant in a cart
        onHost(b.tenant().host(), customerToken(b), "POST", "/api/v1/shop/checkout", checkoutBody(b, a.variantS(), 1, "CASH_ON_DELIVERY", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_UNAVAILABLE"));
        assertThat(available(a, a.variantS())).isEqualTo(1);
    }

    @Test
    void couponsIdempotencyAndCatalogValidation() throws Exception {
        Shop s = shop(10, 10);
        String cust = customerToken(s);
        String h = s.tenant().host();
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/coupons", "{\"code\":\"save10\",\"discountType\":\"PERCENT\",\"value\":10,\"perCustomerLimit\":1}").andExpect(status().isCreated());
        String key = UUID.randomUUID().toString();
        String body = checkoutBody(s, s.variantS(), 1, "CASH_ON_DELIVERY", ",\"couponCode\":\"SAVE10\"");
        // 100.00 - 10% + 50.00 shipping = 140.00; same Idempotency-Key returns the same order and reserves once
        String first = ok(onHost(h, cust, "POST", "/api/v1/shop/checkout", body, key).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor").value(14000)));
        onHost(h, cust, "POST", "/api/v1/shop/checkout", body, key).andExpect(jsonPath("$.orderId").value(read(first, "$.orderId"))).andExpect(jsonPath("$.replay").value(true));
        assertThat(available(s, s.variantS())).isEqualTo(9);
        // per-customer limit
        onHost(h, cust, "POST", "/api/v1/shop/checkout", body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COUPON_INVALID"));

        String dupCombo = """
                {"taxonomySlug":"hats-caps","audience":"ALL","name":"Cap","options":[{"name":"Color","values":["Red","Blue"]}],"variants":[
                 {"sku":"CAP-1-%1$s","priceMinor":100,"optionValues":{"Color":"Red"}},{"sku":"CAP-2-%1$s","priceMinor":100,"optionValues":{"Color":"Red"}}]}
                """.formatted(uniq());
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/products", dupCombo).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_VARIANT"));
        String dupSku = """
                {"taxonomySlug":"hats-caps","audience":"ALL","name":"Cap2","variants":[{"sku":"%s","priceMinor":100,"optionValues":{}}]}
                """.formatted(jdbc.sql("SELECT sku FROM commerce.product_variants WHERE id = :v").param("v", UUID.fromString(s.variantS())).query(String.class).single());
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/products", dupSku).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_TAKEN"));
    }

    @Test
    void clientsAndPatientsAreSeparateWorlds() throws Exception {
        Shop a = shop(1, 1);
        Shop b = shop(1, 1);
        Tenant clinic = onboard("CLINIC");
        // a Google client works on any store, but is nobody on a clinic
        String client = googleClient(a.tenant().host(), "same-person", "Yara");
        String sameClient = googleClient(b.tenant().host(), "same-person", "Yara");
        onHost(b.tenant().host(), sameClient, "GET", "/api/v1/shop/orders", null).andExpect(status().isOk());
        onHost(clinic.host(), client, "GET", "/api/v1/portal/queue", null).andExpect(status().isForbidden());
        onHost(clinic.host(), client, "GET", "/api/v1/clinic/patients", null).andExpect(status().isForbidden());
        // Google sign-in does not exist on a clinic host
        mvc.perform(post("/api/v1/auth/client/google").header("Host", clinic.host()).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"test|x|x@example.com|X\"}")).andExpect(status().isNotFound());
        // a bad Google credential is rejected
        mvc.perform(post("/api/v1/auth/client/google").header("Host", a.tenant().host()).contentType(MediaType.APPLICATION_JSON).content("{\"credential\":\"not-a-google-token-at-all\"}")).andExpect(status().isUnauthorized());
        // a patient works only on the clinic that registered them, never on a store
        PatientLogin patient = patientAt(clinic, "Hala");
        onHost(clinic.host(), patient.token(), "GET", "/api/v1/portal/queue", null).andExpect(status().isOk());
        onHost(a.tenant().host(), patient.token(), "GET", "/api/v1/shop/orders", null).andExpect(status().isForbidden());
        Tenant otherClinic = onboard("CLINIC");
        onHost(otherClinic.host(), patient.token(), "GET", "/api/v1/portal/queue", null).andExpect(status().isForbidden());
        // there is no phone+password registration for shoppers any more, and a patient's password does not open a store
        onHost(a.tenant().host(), null, "POST", "/api/v1/shop/customers/register", "{}").andExpect(status().is4xxClientError());
        onHost(a.tenant().host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"PatientPass1\"}".formatted(patient.phone())).andExpect(status().isUnauthorized());
        // a store owner is not a member of a clinic
        onHost(clinic.host(), null, "POST", "/api/v1/auth/login", "{\"identifier\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(a.tenant().phone())).andExpect(status().isForbidden());
        onHost(clinic.host(), null, "GET", "/api/v1/shop/products", null).andExpect(status().isNotFound());
    }

    @Test
    void guestsCanOrderWithoutAnAccountAndTheShopSeesThemAsReadOnlyClients() throws Exception {
        Shop s = shop(5, 5);
        String h = s.tenant().host();
        // no token at all: price a cart, then order with name + mobile + address, cash on delivery
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", checkoutBody(s, s.variantS(), 2, "CASH_ON_DELIVERY", "")).andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(25000));
        String order = ok(onHost(h, null, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantS(), 2, "CASH_ON_DELIVERY", "")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")));
        String orderId = read(order, "$.orderId");
        // contact details are mandatory, card stays off even if asked for
        onHost(h, null, "POST", "/api/v1/shop/checkout", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}],\"shippingMethodId\":\"%s\",\"paymentMethod\":\"CASH_ON_DELIVERY\"}".formatted(s.variantS(), shippingId(s, "FIXED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTACT_REQUIRED"));
        onHost(h, null, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantS(), 1, "CARD", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_DISABLED"));
        // a guest cannot read orders
        onHost(h, null, "GET", "/api/v1/shop/orders", null).andExpect(status().isUnauthorized());
        // the shop sees the order and the client, and walks it through the statuses
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/orders?status=REQUESTED", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1));
        String clients = ok(onHost(h, s.tenant().access(), "GET", "/api/v1/store/customers", null).andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Mona")).andExpect(jsonPath("$.data[0].hasAccount").value(false)).andExpect(jsonPath("$.data[0].ordersCount").value(1)));
        String clientId = read(clients, "$.data[0].id");
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/customers/" + clientId + "/orders", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        // the shop can read clients but never create or change them
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/customers", "{\"name\":\"Fake\",\"phone\":\"01000000000\"}").andExpect(status().is4xxClientError());
        onHost(h, s.tenant().access(), "PATCH", "/api/v1/store/customers/" + clientId, "{\"name\":\"Changed\"}").andExpect(status().is4xxClientError());
        onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"SHIPPED\"}").andExpect(status().isConflict());   // cannot skip preparing
        for (String next : List.of("PREPARING", "SHIPPED", "RETURNED"))
            onHost(h, s.tenant().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"" + next + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(next));
        assertThat(available(s, s.variantS())).isEqualTo(5);   // returned before arriving: the reserved stock is released, nothing is double counted
        assertThat(onHand(s.variantS())).isEqualTo(5);
        // the shop owner got the in-app notification for the new order
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'NEW_ORDER' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", s.tenant().slug()).query(Long.class).single()).isEqualTo(1);

        // the same phone ordering later while signed in with Google becomes the same client (now with an account)
        String google = googleClient(h, "mona-google", "Mona G");
        onHost(h, google, "POST", "/api/v1/shop/checkout", checkoutBody(s, s.variantM(), 1, "CASH_ON_DELIVERY", "")).andExpect(status().isCreated());
        onHost(h, s.tenant().access(), "GET", "/api/v1/store/customers", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].hasAccount").value(true)).andExpect(jsonPath("$.data[0].ordersCount").value(2));
        onHost(h, google, "GET", "/api/v1/shop/orders", null).andExpect(jsonPath("$.meta.total").value(1));
    }
}
