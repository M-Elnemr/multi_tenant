package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Owner feedback round: order channel, till search and barcodes, returns by item, the Facebook page connect, the WhatsApp account id, the report centre. */
class Round5IntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeHttpGateway.reset(); FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String branch, String variant, String sku, String name, String zones, String barcode) {}

    Shop shop(String barcode) throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "Cotton Tee " + uniq(), sku = "SKU-" + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"Soft cotton","variants":[{"sku":"%s","barcode":%s,"priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(name, sku, barcode == null ? "null" : "\"" + barcode + "\"", branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, branch, JsonPath.read(p, "$.variants[0].id"), sku, name, method, barcode);
    }

    long available(Shop s) {
        return jdbc.sql("SELECT coalesce(sum(quantity_on_hand - quantity_reserved), 0) FROM commerce.inventory_items WHERE variant_id = :v").param("v", UUID.fromString(s.variant())).query(Long.class).single();
    }

    String sell(Shop s, int qty) throws Exception {
        return ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":%d}],\"paymentMethod\":\"CASH\"}".formatted(s.variant(), qty)).andExpect(status().isCreated()));
    }

    @Test
    void ordersRecordWhereTheyCameFromAndCanBeFiltered() throws Exception {
        Shop s = shop(null);
        String h = s.t().host(), owner = s.t().access();
        sell(s, 1);
        String web = JsonPath.read(ok(onHost(h, null, "POST", "/api/v1/shop/checkout", """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona Hassan","phone":"01011112222","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones()), "k-" + uniq()).andExpect(status().isCreated())), "$.orderId");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + web, null).andExpect(jsonPath("$.channel").value("WEBSITE"));
        onHost(h, owner, "GET", "/api/v1/store/orders?channel=POS", null).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].channel").value("POS"));
        onHost(h, owner, "GET", "/api/v1/store/orders?channel=FACEBOOK", null).andExpect(jsonPath("$.data.length()").value(0));
        onHost(h, owner, "GET", "/api/v1/store/orders", null).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void barcodesAreUniqueSearchableAndEditableAndTheTillFindsProductsAsYouType() throws Exception {
        String code = "6221031" + (100000 + (int) (System.nanoTime() % 800000));
        Shop s = shop(code);
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=" + code, null).andExpect(status().isOk()).andExpect(jsonPath("$.variantId").value(s.variant())).andExpect(jsonPath("$.barcode").value(code));
        // the same barcode cannot belong to a second product of the shop
        String other = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Other","brand":"Acme","description":"x","variants":[{"sku":"S-%s","barcode":"%s","priceMinor":1000,"stock":[]}]}
                """.formatted(uniq(), code);
        onHost(h, owner, "POST", "/api/v1/store/products", other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BARCODE_TAKEN"));
        // search: by part of the name, the barcode, the sku; an empty box lists the first products
        onHost(h, owner, "GET", "/api/v1/store/pos/search?q=cotton", null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].available").value(50));
        onHost(h, owner, "GET", "/api/v1/store/pos/search?q=" + code.substring(0, 9), null).andExpect(jsonPath("$[0].variantId").value(s.variant()));
        onHost(h, owner, "GET", "/api/v1/store/pos/search?q=" + s.sku().toLowerCase(), null).andExpect(jsonPath("$[0].variantId").value(s.variant()));
        onHost(h, owner, "GET", "/api/v1/store/pos/search", null).andExpect(jsonPath("$.length()").value(1));
        onHost(h, owner, "GET", "/api/v1/store/pos/search?q=zzzz", null).andExpect(jsonPath("$.length()").value(0));
        onHost(h, null, "GET", "/api/v1/store/pos/search?q=a", null).andExpect(status().isUnauthorized());
        // a barcode can be changed after the product exists, and cleared
        onHost(h, owner, "PATCH", "/api/v1/store/variants/" + s.variant(), "{\"barcode\":\" 5449000000996 \"}").andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=5449000000996", null).andExpect(jsonPath("$.variantId").value(s.variant()));
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=" + code, null).andExpect(status().isNotFound());
        onHost(h, owner, "PATCH", "/api/v1/store/variants/" + s.variant(), "{\"barcode\":\"\"}").andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/pos/lookup?code=5449000000996", null).andExpect(status().isNotFound());
        // another shop may use the same number for its own product
        shop(code);
    }

    @Test
    void returningPiecesRestocksOnlyThemRefundsThemAndIssuesACreditNote() throws Exception {
        Shop s = shop("6221031999111");
        String h = s.t().host(), owner = s.t().access();
        String sale = sell(s, 3);
        String orderId = JsonPath.read(sale, "$.orderId"), number = JsonPath.read(sale, "$.orderNumber");
        assertThat(available(s)).isEqualTo(47);

        String found = ok(onHost(h, owner, "GET", "/api/v1/store/pos/returns/order?q=" + number, null).andExpect(status().isOk()).andExpect(jsonPath("$.canReturn").value(true)).andExpect(jsonPath("$.items[0].barcode").value("6221031999111")).andExpect(jsonPath("$.items[0].returnable").value(3)));
        String item = JsonPath.read(found, "$.items[0].orderItemId");
        onHost(h, owner, "GET", "/api/v1/store/pos/returns/order?q=NOPE-0000", null).andExpect(status().isNotFound());

        onHost(h, owner, "POST", "/api/v1/store/pos/returns", "{\"orderId\":\"%s\",\"items\":[{\"orderItemId\":\"%s\",\"quantity\":1}],\"reason\":\"Wrong size\"}".formatted(orderId, item))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.refundMinor").value(20000)).andExpect(jsonPath("$.orderFullyReturned").value(false)).andExpect(jsonPath("$.creditNote.number").value(org.hamcrest.Matchers.startsWith("CN-")));
        assertThat(available(s)).isEqualTo(48);   // one piece back, not three
        onHost(h, owner, "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.status").value("ARRIVED")).andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"));
        onHost(h, owner, "GET", "/api/v1/store/pos/returns/order?q=" + number, null).andExpect(jsonPath("$.items[0].returnable").value(2));

        // more than is left is refused and changes nothing
        onHost(h, owner, "POST", "/api/v1/store/pos/returns", "{\"orderId\":\"%s\",\"items\":[{\"orderItemId\":\"%s\",\"quantity\":3}]}".formatted(orderId, item)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_EXCEEDS_SOLD"));
        assertThat(available(s)).isEqualTo(48);

        onHost(h, owner, "POST", "/api/v1/store/pos/returns", "{\"orderId\":\"%s\",\"items\":[{\"orderItemId\":\"%s\",\"quantity\":2}]}".formatted(orderId, item))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.orderFullyReturned").value(true));
        assertThat(available(s)).isEqualTo(50);
        onHost(h, owner, "GET", "/api/v1/store/orders/" + orderId, null).andExpect(jsonPath("$.status").value("REFUNDED")).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        onHost(h, owner, "GET", "/api/v1/store/orders/" + orderId + "/invoices", null).andExpect(jsonPath("$.length()").value(3));   // the invoice and one credit note per return
        onHost(h, owner, "GET", "/api/v1/store/pos/returns", null).andExpect(jsonPath("$.length()").value(2));
        // nothing left to return
        onHost(h, owner, "POST", "/api/v1/store/pos/returns", "{\"orderId\":\"%s\",\"items\":[{\"orderItemId\":\"%s\",\"quantity\":1}]}".formatted(orderId, item)).andExpect(status().isConflict());
        // another shop cannot see or return this order
        Shop other = shop(null);
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/pos/returns/order?q=" + number, null).andExpect(status().isNotFound());
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/pos/returns", "{\"orderId\":\"%s\",\"items\":[{\"orderItemId\":\"%s\",\"quantity\":1}]}".formatted(orderId, item)).andExpect(status().isNotFound());
    }

    @Test
    void connectingAFacebookPageReadsThePageThroughMeAndExplainsWhatWentWrong() throws Exception {
        Tenant t = onboard("STORE");
        String page = "1" + (1_000_000_000L + System.nanoTime() % 800_000_000L);
        String conn = "{\"platform\":\"FACEBOOK\",\"externalId\":\"%s\",\"accessToken\":\"%s\"}";
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", conn.formatted(page, "NOPERM-token-0123456789012")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("META_MISSING_PERMISSION"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", conn.formatted(page, "EXPIRED-token-01234567890")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("META_TOKEN_EXPIRED"));
        // a token that belongs to another page (or to a person) is not accepted for this page
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", conn.formatted(page, "PAGE-OF-777777777-0123456789012")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("META_TOKEN_NOT_PAGE"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/channels", conn.formatted(page, "PAGE-OF-" + page + "-0123456789012")).andExpect(status().isCreated()).andExpect(jsonPath("$.platform").value("FACEBOOK"));
    }

    @Test
    void theWhatsAppAccountIdIsSavedOnlyWhenItOwnsTheConnectedNumber() throws Exception {
        Tenant t = onboard("STORE");
        String h = t.host(), owner = t.access();
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp/waba", "{\"wabaId\":\"123456789012345\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_CHANNEL"));
        String phone = "9" + (10_000_000_000L + System.nanoTime() % 80_000_000_000L);
        FakeMetaGraph.WABA_PHONE = phone;
        onHost(h, owner, "POST", "/api/v1/store/ai/channels", "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted(phone)).andExpect(status().isCreated());
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp/waba", "{\"wabaId\":\"abc\"}").andExpect(status().isBadRequest());
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp/waba", "{\"wabaId\":\"123456789012345\"}").andExpect(status().isOk()).andExpect(jsonPath("$.wabaId").value("123456789012345"));
        assertThat(jdbc.sql("SELECT waba_id FROM chat.channels WHERE platform = 'WHATSAPP' AND external_id = :e").param("e", phone).query(String.class).single()).isEqualTo("123456789012345");
        FakeMetaGraph.WABA_PHONE = "000";   // now Meta says that account owns another number
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp/waba", "{\"wabaId\":\"999999999999999\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WABA_MISMATCH"));
        FakeMetaGraph.WABA_PHONE = "99900011122";
    }

    @Test
    void theReportCentreSplitsSalesByChannelAndPaymentAndComparesWithTheLastPeriod() throws Exception {
        Shop s = shop(null);
        String h = s.t().host(), owner = s.t().access();
        sell(s, 2);
        sell(s, 1);
        String r = ok(onHost(h, owner, "GET", "/api/v1/store/reports/overview?days=7", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(r, "$.current.orders")).intValue()).isEqualTo(2);
        assertThat(((Number) JsonPath.read(r, "$.current.revenueMinor")).longValue()).isEqualTo(60000L);
        assertThat(((Number) JsonPath.read(r, "$.current.aovMinor")).longValue()).isEqualTo(30000L);
        assertThat(((Number) JsonPath.read(r, "$.previous.orders")).intValue()).isEqualTo(0);
        assertThat((String) JsonPath.read(r, "$.byChannel[0].key")).isEqualTo("POS");
        assertThat((java.util.List<?>) JsonPath.read(r, "$.trend")).hasSize(7);
        assertThat(((Number) JsonPath.read(r, "$.topProducts[0].units")).intValue()).isEqualTo(3);
        onHost(h, null, "GET", "/api/v1/store/reports/overview", null).andExpect(status().isUnauthorized());
    }
}
