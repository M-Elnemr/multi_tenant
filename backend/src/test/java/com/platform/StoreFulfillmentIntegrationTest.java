package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/** Governorate delivery zones, COD controls, confirmation, tracking, guest order tracking and returns. */
class StoreFulfillmentIntegrationTest extends IntegrationTestBase {
    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, String variant, String zonesMethod) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","variants":[{"sku":"T-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":20}]}]}
                """.formatted(uniq(), uniq(), branch);
        String variant = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.variants[0].id");
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones",
                "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":1000,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Delta\",\"governorateCodes\":[\"DKH\"],\"feeMinor\":8000,\"freeAboveMinor\":30000,\"etaMinDays\":2,\"etaMaxDays\":4}").andExpect(status().isOk());
        return new Shop(t, variant, method);
    }

    String cart(Shop s, String gov, String phone, int qty) {
        return """
                {"items":[{"variantId":"%s","quantity":%d}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona","phone":"%s","addressLine1":"1 Tahrir St","governorateCode":"%s","area":"Dokki","landmark":"Next to the bank","phone2":"01099998888"}}
                """.formatted(s.variant(), qty, s.zonesMethod(), phone, gov);
    }

    @Test
    void zonePricingEtaCodFeeAndMissingZone() throws Exception {
        Shop s = shop();
        String h = s.t().host();
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, "CAI", "01011112222", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingMinor").value(5000)).andExpect(jsonPath("$.codFeeMinor").value(1000)).andExpect(jsonPath("$.totalMinor").value(26000))
                .andExpect(jsonPath("$.etaMinDays").value(1)).andExpect(jsonPath("$.etaMaxDays").value(2));
        // free delivery above the threshold in the Delta zone; no COD fee configured there
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, "DKH", "01011112222", 2)).andExpect(jsonPath("$.shippingMinor").value(0)).andExpect(jsonPath("$.totalMinor").value(40000));
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, "ALX", "01011112222", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_DELIVERY_TO_GOVERNORATE"));
        onHost(h, null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.shippingZones.length()").value(2));
        // a governorate lives in one zone only: moving CAI into Delta removes it from Greater Cairo
        String zones = ok(onHost(h, s.t().access(), "GET", "/api/v1/store/shipping-zones", null));
        String delta = JsonPath.<java.util.List<String>>read(zones, "$[?(@.name=='Delta')].id").get(0);
        onHost(h, s.t().access(), "PATCH", "/api/v1/store/shipping-zones/" + delta, "{\"governorateCodes\":[\"DKH\",\"CAI\"]}").andExpect(status().isOk());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, "CAI", "01011112222", 1)).andExpect(jsonPath("$.shippingMinor").value(8000));
    }

    @Test
    void confirmationTrackingGuestTrackingAndReturnFlow() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access(), phone = "01012345678";
        String order = ok(onHost(h, null, "POST", "/api/v1/shop/checkout", cart(s, "CAI", phone, 1), "key-" + uniq()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.totalMinor").value(26000)));
        String id = JsonPath.read(order, "$.orderId"), number = JsonPath.read(order, "$.orderNumber");

        onHost(h, owner, "GET", "/api/v1/store/orders/summary", null).andExpect(jsonPath("$.unconfirmed").value(1));
        onHost(h, owner, "GET", "/api/v1/store/orders?unconfirmed=true", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + id + "/confirm", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.confirmedAt").isNotEmpty());
        onHost(h, owner, "GET", "/api/v1/store/orders/summary", null).andExpect(jsonPath("$.unconfirmed").value(0));
        onHost(h, owner, "GET", "/api/v1/store/orders/" + id, null).andExpect(jsonPath("$.governorateCode").value("CAI")).andExpect(jsonPath("$.landmark").value("Next to the bank")).andExpect(jsonPath("$.customerHistory.deliveredCount").value(0));

        for (String st : new String[]{"PREPARING", "SHIPPED"}) onHost(h, owner, "POST", "/api/v1/store/orders/" + id + "/status", "{\"status\":\"" + st + "\"}").andExpect(status().isOk());
        onHost(h, owner, "PATCH", "/api/v1/store/orders/" + id + "/tracking", "{\"courierName\":\"Bosta\",\"trackingNumber\":\"BO123\",\"trackingUrl\":\"https://bosta.co/t/BO123\"}").andExpect(status().isOk());
        onHost(h, owner, "PATCH", "/api/v1/store/orders/" + id + "/tracking", "{\"trackingUrl\":\"javascript:alert(1)\"}").andExpect(status().isBadRequest());

        // a guest follows the order with number + phone only; a wrong phone learns nothing
        String body = "{\"orderNumber\":\"%s\",\"phone\":\"%s\"}";
        onHost(h, null, "POST", "/api/v1/shop/track", body.formatted(number, phone)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.courierName").value("Bosta")).andExpect(jsonPath("$.trackingNumber").value("BO123")).andExpect(jsonPath("$.canReturn").value(false));
        onHost(h, null, "POST", "/api/v1/shop/track", body.formatted(number, "01000000000")).andExpect(status().isNotFound());

        onHost(h, owner, "POST", "/api/v1/store/orders/" + id + "/status", "{\"status\":\"ARRIVED\"}").andExpect(status().isOk());
        onHost(h, null, "POST", "/api/v1/shop/track", body.formatted(number, phone)).andExpect(jsonPath("$.canReturn").value(true)).andExpect(jsonPath("$.deliveredAt").isNotEmpty());

        // return: request -> approve -> goods received -> order RETURNED, stock restored
        String ret = "{\"orderNumber\":\"%s\",\"phone\":\"%s\",\"reason\":\"SIZE\",\"details\":\"Too small\"}".formatted(number, phone);
        onHost(h, null, "POST", "/api/v1/shop/track/return", ret).andExpect(status().isOk()).andExpect(jsonPath("$.returnRequest.status").value("REQUESTED")).andExpect(jsonPath("$.canReturn").value(false));
        onHost(h, null, "POST", "/api/v1/shop/track/return", ret).andExpect(status().isConflict());
        String rid = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/returns", null).andExpect(jsonPath("$.meta.total").value(1))), "$.data[0].id");
        onHost(h, owner, "POST", "/api/v1/store/returns/" + rid + "/decision", "{\"status\":\"RECEIVED\"}").andExpect(status().isConflict());   // must be approved first
        onHost(h, owner, "POST", "/api/v1/store/returns/" + rid + "/decision", "{\"status\":\"APPROVED\",\"note\":\"Send it back\"}").andExpect(status().isOk());
        onHost(h, owner, "POST", "/api/v1/store/returns/" + rid + "/decision", "{\"status\":\"RECEIVED\"}").andExpect(status().isOk());
        onHost(h, owner, "GET", "/api/v1/store/orders/" + id, null).andExpect(jsonPath("$.status").value("RETURNED")).andExpect(jsonPath("$.customerHistory.returnedCount").value(1)).andExpect(jsonPath("$.customerHistory.deliveredCount").value(1));
    }

    @Test
    void blockedPhonesClosedShopAndMinimumOrder() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "POST", "/api/v1/store/phone-flags", "{\"phone\":\"01055556666\",\"blocked\":true,\"note\":\"refused 3 times\"}").andExpect(status().isOk());
        onHost(h, null, "POST", "/api/v1/shop/checkout", cart(s, "CAI", "01055556666", 1), "k-" + uniq()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORDER_NOT_ALLOWED"));
        onHost(h, null, "POST", "/api/v1/shop/checkout", cart(s, "CAI", "01077778888", 1), "k-" + uniq()).andExpect(status().isCreated());

        onHost(h, owner, "PATCH", "/api/v1/store/profile", "{\"minOrderMinor\":50000}").andExpect(status().isOk());
        onHost(h, null, "POST", "/api/v1/shop/cart/quote", cart(s, "CAI", "01077778888", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MIN_ORDER_NOT_REACHED"));
        onHost(h, owner, "PATCH", "/api/v1/store/profile", "{\"minOrderMinor\":0,\"isOpen\":false}").andExpect(status().isOk());
        onHost(h, null, "POST", "/api/v1/shop/checkout", cart(s, "CAI", "01077778888", 1), "k-" + uniq()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SHOP_CLOSED"));
        assertThat(ok(onHost(h, owner, "GET", "/api/v1/store/phone-flags", null))).contains("01055556666");
    }
}
