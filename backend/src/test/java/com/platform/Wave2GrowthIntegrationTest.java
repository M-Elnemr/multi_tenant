package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.commerce.CapiService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Loyalty points, bought-together, segments and free-window broadcasts, product feeds and server-side Meta events. */
class Wave2GrowthIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired CapiService capi;

    @BeforeEach
    void clean() { FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variantA, String variantB, String slugA, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String[] v = new String[2], slug = new String[1];
        for (int i = 0; i < 2; i++) {
            String body = """
                    {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Item%d %s","brand":"Acme","description":"Nice item","variants":[{"sku":"S%d-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                    """.formatted(i, uniq(), i, uniq(), branch);
            String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
            v[i] = JsonPath.read(p, "$.variants[0].id");
            if (i == 0) slug[0] = JsonPath.read(p, "$.slug");
        }
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), v[0], v[1], slug[0], method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    String order(Shop s, String phone, String coupon, String... variantQty) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < variantQty.length; i += 2) items.append(i > 0 ? "," : "").append("{\"variantId\":\"%s\",\"quantity\":%s}".formatted(variantQty[i], variantQty[i + 1]));
        String body = """
                {"items":[%s],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY"%s,
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(items, s.zones(), coupon == null ? "" : ",\"couponCode\":\"" + coupon + "\"", phone);
        return JsonPath.read(ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated())), "$.orderId");
    }

    void deliver(Shop s, String orderId) throws Exception {
        for (String st : new String[]{"PREPARING", "SHIPPED", "ARRIVED"}) onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/orders/" + orderId + "/status", "{\"status\":\"" + st + "\"}").andExpect(status().isOk());
    }

    @Test
    void pointsAreEarnedOnDeliveryTurnedIntoOneTimeCouponsAndTakenBackOnReturns() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access(), phone = phone();
        onHost(h, owner, "PUT", "/api/v1/store/loyalty", "{\"enabled\":true,\"earnPercent\":5,\"minRedeem\":20}").andExpect(status().isOk());
        onHost(h, owner, "PUT", "/api/v1/store/loyalty", "{\"earnPercent\":99}").andExpect(status().isBadRequest());
        String client = googleClient(h);
        onHost(h, client, "PUT", "/api/v1/shop/me", "{\"name\":\"Mona Hassan\",\"phone\":\"" + phone + "\"}").andExpect(status().isOk());

        String o1 = order(s, phone, null, s.variantA(), "5");   // 1000.00 of goods -> 50 points
        onHost(h, client, "GET", "/api/v1/shop/loyalty", null).andExpect(jsonPath("$.points").value(0));   // nothing before delivery
        deliver(s, o1);
        onHost(h, client, "GET", "/api/v1/shop/loyalty", null).andExpect(jsonPath("$.points").value(50)).andExpect(jsonPath("$.history[0].reason").value("EARN"));

        // points become a one-time coupon worth that many currency units
        onHost(h, client, "POST", "/api/v1/shop/loyalty/redeem", "{\"points\":10}").andExpect(status().isBadRequest());   // below the minimum
        onHost(h, client, "POST", "/api/v1/shop/loyalty/redeem", "{\"points\":500}").andExpect(status().isBadRequest());   // more than owned
        String coupon = JsonPath.read(ok(onHost(h, client, "POST", "/api/v1/shop/loyalty/redeem", "{\"points\":30}").andExpect(status().isOk()).andExpect(jsonPath("$.valueMinor").value(3000))), "$.couponCode");
        onHost(h, client, "GET", "/api/v1/shop/loyalty", null).andExpect(jsonPath("$.points").value(20));
        String o2 = order(s, phone, coupon, s.variantA(), "1");
        onHost(h, owner, "GET", "/api/v1/store/orders/" + o2, null).andExpect(jsonPath("$.discountMinor").value(3000));
        onHost(h, null, "POST", "/api/v1/shop/checkout", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":1}],\"shippingMethodId\":\"%s\",\"paymentMethod\":\"CASH_ON_DELIVERY\",\"couponCode\":\"%s\",\"address\":{\"recipientName\":\"Mona Hassan\",\"phone\":\"%s\",\"addressLine1\":\"12 Tahrir Street, Dokki\",\"governorateCode\":\"CAI\",\"area\":\"Dokki\"}}"
                .formatted(s.variantA(), s.zones(), coupon, phone), "k-" + uniq()).andExpect(status().isBadRequest());   // one use only

        // a delivered order that comes back takes its points back
        String o3 = order(s, phone, null, s.variantB(), "5");
        deliver(s, o3);
        onHost(h, client, "GET", "/api/v1/shop/loyalty", null).andExpect(jsonPath("$.points").value(70));
        onHost(h, owner, "POST", "/api/v1/store/orders/" + o3 + "/status", "{\"status\":\"RETURNED\"}").andExpect(status().isOk());
        onHost(h, client, "GET", "/api/v1/shop/loyalty", null).andExpect(jsonPath("$.points").value(20));
        onHost(h, owner, "GET", "/api/v1/store/loyalty", null).andExpect(jsonPath("$.top[0].lifetimePoints").value(100));

        // off for the shop: nothing is earned or redeemed; other shops are separate
        Shop other = shop();
        onHost(other.t().host(), googleClient(other.t().host()), "POST", "/api/v1/shop/loyalty/redeem", "{\"points\":30}").andExpect(status().isBadRequest());
        onHost(h, null, "GET", "/api/v1/shop/loyalty", null).andExpect(status().isUnauthorized());
    }

    @Test
    void boughtTogetherComesFromRealOrdersOnly() throws Exception {
        Shop s = shop();
        String h = s.t().host();
        onHost(h, null, "GET", "/api/v1/shop/products/" + s.slugA() + "/together", null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        order(s, phone(), null, s.variantA(), "1", s.variantB(), "1");
        order(s, phone(), null, s.variantA(), "2", s.variantB(), "1");
        String res = ok(onHost(h, null, "GET", "/api/v1/shop/products/" + s.slugA() + "/together", null).andExpect(jsonPath("$.length()").value(1)));
        assertThat((String) JsonPath.read(res, "$[0].name")).startsWith("Item1");
        assertThat(((Number) JsonPath.read(res, "$[0].togetherOrders")).intValue()).isEqualTo(2);
    }

    @Test
    void segmentsAndBroadcastsNeverCostMoney() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String vip = phone(), fresh = phone(), silent = phone();
        for (int i = 0; i < 3; i++) deliver(s, order(s, vip, null, s.variantA(), "1"));
        order(s, fresh, null, s.variantA(), "1");
        order(s, silent, null, s.variantA(), "1");
        String seg = ok(onHost(h, owner, "GET", "/api/v1/store/segments", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(seg, "$.segments.VIP")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(seg, "$.segments.NEW")).intValue()).isEqualTo(2);
        onHost(h, owner, "GET", "/api/v1/store/segments/VIP", null).andExpect(jsonPath("$.customers[0].orders").value(3));
        onHost(h, owner, "GET", "/api/v1/store/segments/NOPE", null).andExpect(status().isBadRequest());

        // consent: only 'fresh' agreed to WhatsApp; the window is open only for 'fresh' too, so exactly one free message goes out
        jdbc.sql("UPDATE commerce.orders SET whatsapp_number = :w, whatsapp_opt_in_at = now() WHERE tenant_id = :t AND customer_phone_snapshot = :p").param("w", "+2" + fresh).param("t", s.id()).param("p", "+2" + fresh).update();
        jdbc.sql("UPDATE commerce.orders SET whatsapp_number = :w, whatsapp_opt_in_at = now() WHERE tenant_id = :t AND customer_phone_snapshot = :p").param("w", "+2" + silent).param("t", s.id()).param("p", "+2" + silent).update();
        onHost(h, owner, "POST", "/api/v1/store/ai/channels", "{\"platform\":\"WHATSAPP\",\"externalId\":\"%s\",\"accessToken\":\"EAAG-secret-token-0123456789\"}".formatted("7" + System.nanoTime())).andExpect(status().isCreated());
        jdbc.sql("INSERT INTO chat.wa_windows (tenant_id, number, last_inbound_at) VALUES (:t, :n, now())").param("t", s.id()).param("n", "2" + fresh).update();
        onHost(h, owner, "GET", "/api/v1/store/segments/NEW", null).andExpect(jsonPath("$.preview.audience").value(2)).andExpect(jsonPath("$.preview.withConsent").value(2)).andExpect(jsonPath("$.preview.reachableNow").value(1));
        FakeMetaGraph.SENT.clear();
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"NEW\",\"message\":\"Hi {name}, new arrivals today\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(1)).andExpect(jsonPath("$.skippedNoWindow").value(1)).andExpect(jsonPath("$.skippedNoConsent").value(0));
        assertThat(FakeMetaGraph.SENT.stream().filter(x -> "text".equals(x.body().get("type"))).count()).isEqualTo(1);
        assertThat(FakeMetaGraph.SENT.stream().noneMatch(x -> "template".equals(x.body().get("type")))).isTrue();   // never a paid template
        assertThat(String.valueOf(FakeMetaGraph.SENT.get(0).body())).contains("Hi Mona");
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"NEW\",\"message\":\"Second one today\"}").andExpect(status().isConflict());   // one a day
        onHost(h, owner, "GET", "/api/v1/store/broadcasts", null).andExpect(jsonPath("$.length()").value(1));
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "GET", "/api/v1/store/segments", null).andExpect(jsonPath("$.segments.VIP").value(0));
        onHost(h, null, "GET", "/api/v1/store/segments", null).andExpect(status().isUnauthorized());
    }

    @Test
    void feedsListRealPricesAndStockAndMetaGetsPurchasesFromTheServer() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String feed = ok(onHost(h, null, "GET", "/api/v1/shop/feed", null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)));
        assertThat((String) JsonPath.read(feed, "$.items[0].price")).isEqualTo("200.00 EGP");
        assertThat((String) JsonPath.read(feed, "$.items[0].availability")).isEqualTo("in stock");
        assertThat((String) JsonPath.read(feed, "$.items[0].link")).contains("/products/");
        assertThat(((Number) JsonPath.read(feed, "$.items[0].stock")).intValue()).isEqualTo(50);

        // server-side events need a pixel and a token, and stay off until switched on
        String id = order(s, phone(), null, s.variantA(), "1");
        assertThat(capi.send(s.id(), UUID.fromString(id))).isFalse();
        onHost(h, owner, "PUT", "/api/v1/store/tracking", "{\"enabled\":true,\"token\":\"EAAG-capi-token-0123456789\"}").andExpect(status().isBadRequest());   // no pixel yet
        jdbc.sql("UPDATE commerce.store_profiles SET meta_pixel_id = '123456789012345' WHERE tenant_id = :t").param("t", s.id()).update();
        onHost(h, owner, "PUT", "/api/v1/store/tracking", "{\"enabled\":true}").andExpect(status().isBadRequest());   // no token yet
        onHost(h, owner, "PUT", "/api/v1/store/tracking", "{\"enabled\":true,\"token\":\"EAAG-capi-token-0123456789\"}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.hasToken").value(true));
        FakeMetaGraph.SENT.clear();
        assertThat(capi.send(s.id(), UUID.fromString(id))).isTrue();
        var sent = FakeMetaGraph.SENT.stream().filter(x -> x.path().equals("123456789012345/events")).findFirst().orElseThrow();
        assertThat(sent.token()).isEqualTo("EAAG-capi-token-0123456789");
        String body = String.valueOf(sent.body());
        assertThat(body).contains("Purchase").contains("EGP").doesNotContain("010");   // the phone number is only sent hashed
        onHost(h, owner, "GET", "/api/v1/store/tracking", null).andExpect(jsonPath("$.lastStatus").value("OK"));
        onHost(h, owner, "PUT", "/api/v1/store/tracking", "{\"enabled\":false}").andExpect(status().isOk());
        assertThat(capi.send(s.id(), UUID.fromString(id))).isFalse();
    }
}
