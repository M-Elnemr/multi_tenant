package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.platform.OpsWatchService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** A/B tests on the announcement bar, revenue attribution, the AI store builder, WhatsApp consent at checkout, the ops watchdog and the Copilot's new tools. */
class Wave4LeadIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired OpsWatchService ops;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","variants":[{"sku":"T-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String variant = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated())), "$.variants[0].id");
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), variant, method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    Map<String, String> order(Shop s, boolean consent) throws Exception {
        String ph = phone();
        String body = """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY","whatsappConsent":%s,
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones(), consent, ph);
        String res = ok(onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated()));
        return Map.of("id", JsonPath.read(res, "$.orderId"), "number", JsonPath.read(res, "$.orderNumber"), "phone", ph);
    }

    @Test
    void anAnnouncementTestSplitsVisitorsCountsOnceAndOnlyAnOwnerDecidesTheWinner() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, null, "GET", "/api/v1/shop/experiments/active", null).andExpect(status().isOk()).andExpect(jsonPath("$.id").doesNotExist());
        onHost(h, owner, "POST", "/api/v1/store/experiments", "{\"a\":\"Free delivery today\",\"b\":\"Free delivery today\"}").andExpect(status().isBadRequest());
        String exp = ok(onHost(h, owner, "POST", "/api/v1/store/experiments", "{\"name\":\"Delivery wording\",\"a\":\"Free delivery over 500\",\"b\":\"Order today, delivered in 2 days\"}").andExpect(status().isCreated()));
        String id = JsonPath.read(exp, "$.id");
        onHost(h, null, "GET", "/api/v1/shop/experiments/active", null).andExpect(jsonPath("$.a").value("Free delivery over 500")).andExpect(jsonPath("$.b").value("Order today, delivered in 2 days"));

        // each visitor counts once; an order counts once and only when it is a real, fresh order of this shop
        String ev = "/api/v1/shop/experiments/" + id + "/event";
        onHost(h, null, "POST", ev, "{\"variant\":\"A\",\"type\":\"view\",\"clientKey\":\"visitor-one-1\"}").andExpect(status().isNoContent());
        onHost(h, null, "POST", ev, "{\"variant\":\"A\",\"type\":\"view\",\"clientKey\":\"visitor-one-1\"}").andExpect(status().isNoContent());
        onHost(h, null, "POST", ev, "{\"variant\":\"B\",\"type\":\"view\",\"clientKey\":\"visitor-two-2\"}").andExpect(status().isNoContent());
        onHost(h, null, "POST", ev, "{\"variant\":\"C\",\"type\":\"view\",\"clientKey\":\"visitor-bad-3\"}").andExpect(status().isBadRequest());
        onHost(h, null, "POST", ev, "{\"variant\":\"A\",\"type\":\"order\",\"clientKey\":\"visitor-one-1\",\"orderNumber\":\"ST-FAKE-1\"}").andExpect(status().isNoContent());
        var o = order(s, false);
        onHost(h, null, "POST", ev, "{\"variant\":\"B\",\"type\":\"order\",\"clientKey\":\"visitor-two-2\",\"orderNumber\":\"" + o.get("number") + "\"}").andExpect(status().isNoContent());
        onHost(h, null, "POST", ev, "{\"variant\":\"B\",\"type\":\"order\",\"clientKey\":\"visitor-two-2\",\"orderNumber\":\"" + o.get("number") + "\"}").andExpect(status().isNoContent());
        String r = ok(onHost(h, owner, "GET", "/api/v1/store/experiments", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(r, "$[0].viewsA")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(r, "$[0].viewsB")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(r, "$[0].ordersA")).intValue()).isZero();
        assertThat(((Number) JsonPath.read(r, "$[0].ordersB")).intValue()).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(r, "$[0].enoughData")).isFalse();   // far too little data to call a winner
        assertThat((Object) JsonPath.read(r, "$[0].recommendation")).isNull();

        // with plenty of data the better text is recommended, but nothing changes until the owner applies it
        jdbc.sql("DELETE FROM commerce.experiment_events WHERE experiment_id = :e").param("e", UUID.fromString(id)).update();
        for (int i = 0; i < 200; i++) {
            jdbc.sql("INSERT INTO commerce.experiment_events (experiment_id, variant, type, client_key) VALUES (:e, 'A', 'view', :c), (:e, 'B', 'view', :d)").param("e", UUID.fromString(id)).param("c", "va-" + i + "-" + uniq()).param("d", "vb-" + i + "-" + uniq()).update();
        }
        for (int i = 0; i < 6; i++) jdbc.sql("INSERT INTO commerce.experiment_events (experiment_id, variant, type, client_key, order_number) VALUES (:e, 'A', 'order', :c, :n)").param("e", UUID.fromString(id)).param("c", "oa" + i + uniq()).param("n", "NA-" + i).update();
        for (int i = 0; i < 24; i++) jdbc.sql("INSERT INTO commerce.experiment_events (experiment_id, variant, type, client_key, order_number) VALUES (:e, 'B', 'order', :c, :n)").param("e", UUID.fromString(id)).param("c", "ob" + i + uniq()).param("n", "NB-" + i).update();
        onHost(h, owner, "GET", "/api/v1/store/experiments", null).andExpect(jsonPath("$[0].significant").value(true)).andExpect(jsonPath("$[0].recommendation").value("B")).andExpect(jsonPath("$[0].rateB").value(12.0));
        assertThat(jdbc.sql("SELECT coalesce(announcement, '') FROM commerce.store_profiles WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEmpty();
        onHost(h, owner, "POST", "/api/v1/store/experiments/" + id + "/apply", "{\"winner\":\"B\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED")).andExpect(jsonPath("$.winner").value("B"));
        assertThat(jdbc.sql("SELECT announcement FROM commerce.store_profiles WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("Order today, delivered in 2 days");
        onHost(h, null, "GET", "/api/v1/shop/experiments/active", null).andExpect(jsonPath("$.id").doesNotExist());   // stopped
        onHost(h, null, "POST", ev, "{\"variant\":\"A\",\"type\":\"view\",\"clientKey\":\"late-visitor-9\"}").andExpect(status().isNoContent());
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "POST", "/api/v1/store/experiments/" + id + "/apply", "{\"winner\":\"A\"}").andExpect(status().isNotFound());
        onHost(h, null, "GET", "/api/v1/store/experiments", null).andExpect(status().isUnauthorized());
    }

    @Test
    void revenueIsSplitByWhereItCameFromAndConsentComesOnlyFromAnExplicitTick() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        var web = order(s, true);
        var plain = order(s, false);
        onHost(h, owner, "POST", "/api/v1/store/pos/sales", "{\"items\":[{\"variantId\":\"%s\",\"quantity\":2}],\"paymentMethod\":\"CASH\"}".formatted(s.variant())).andExpect(status().isCreated());
        String a = ok(onHost(h, owner, "GET", "/api/v1/store/attribution?days=30", null).andExpect(status().isOk()));
        assertThat(((Number) JsonPath.read(a, "$.totalOrders")).intValue()).isEqualTo(3);
        assertThat(((Number) JsonPath.read(a, "$.totalRevenueMinor")).longValue()).isEqualTo(2 * 25000L + 40000L);   // two web orders (200 + 50 delivery) and one POS sale of 400
        assertThat((java.util.List<?>) JsonPath.read(a, "$.bySource[?(@.source=='POS')]")).hasSize(1);
        assertThat((java.util.List<?>) JsonPath.read(a, "$.bySource[?(@.source=='WEB')]")).hasSize(1);
        assertThat(((Number) JsonPath.read(a, "$.aiSellerRevenueMinor")).longValue()).isZero();
        onHost(h, null, "GET", "/api/v1/store/attribution", null).andExpect(status().isUnauthorized());

        // the tick is the customer's agreement; nothing is assumed
        assertThat(jdbc.sql("SELECT whatsapp_opt_in_at IS NOT NULL FROM commerce.orders WHERE id = :o").param("o", UUID.fromString(web.get("id"))).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT whatsapp_opt_in_at IS NOT NULL FROM commerce.orders WHERE id = :o").param("o", UUID.fromString(plain.get("id"))).query(Boolean.class).single()).isFalse();
        onHost(h, owner, "GET", "/api/v1/store/segments", null).andExpect(jsonPath("$.withWhatsappConsent").value(1));
    }

    @Test
    void theStoreBuilderOnlyCreatesDraftsAndNeverOverwritesWhatTheOwnerWrote() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "POST", "/api/v1/store/builder", "{\"description\":\"short\"}").andExpect(status().isBadRequest());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"shortDescription\":\"Cotton basics for everyday\",\"about\":\"We sell soft cotton clothes.\",\"template\":\"atelier\",\"products\":[{\"name\":\"Basic tee\",\"description\":\"Soft cotton\",\"price\":250},{\"name\":\"Plain hoodie\",\"description\":\"Warm\",\"price\":600}]}"));
        String r = ok(onHost(h, owner, "POST", "/api/v1/store/builder", "{\"description\":\"I sell soft cotton basics for young men in Cairo\",\"language\":\"en\"}").andExpect(status().isOk()).andExpect(jsonPath("$.products.length()").value(2)).andExpect(jsonPath("$.suggestedTemplate").value("atelier")).andExpect(jsonPath("$.descriptionSaved").value(true)));
        assertThat(((Number) JsonPath.read(r, "$.products[1].priceMinor")).longValue()).isEqualTo(60000L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.products WHERE tenant_id = :t AND status = 'DRAFT'").param("t", s.id()).query(Long.class).single()).isEqualTo(2);
        onHost(h, null, "GET", "/api/v1/shop/products", null).andExpect(jsonPath("$.meta.total").value(1));   // drafts are invisible to shoppers
        assertThat(jdbc.sql("SELECT short_description FROM commerce.store_profiles WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("Cotton basics for everyday");

        // a second run keeps the owner's text
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("{\"shortDescription\":\"Something else\",\"about\":\"Other\",\"products\":[]}"));
        onHost(h, owner, "POST", "/api/v1/store/builder", "{\"description\":\"I sell soft cotton basics for young men in Cairo\"}").andExpect(jsonPath("$.descriptionSaved").value(false));
        assertThat(jdbc.sql("SELECT short_description FROM commerce.store_profiles WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("Cotton basics for everyday");

        FakeAiProvider.SCRIPT.add(turns -> Turn.model("not json at all"));
        onHost(h, owner, "POST", "/api/v1/store/builder", "{\"description\":\"I sell soft cotton basics for young men in Cairo\"}").andExpect(status().isBadGateway());
        FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("down"); });
        onHost(h, owner, "POST", "/api/v1/store/builder", "{\"description\":\"I sell soft cotton basics for young men in Cairo\"}").andExpect(status().isServiceUnavailable());
    }

    @Test
    void theWatchdogReadsOnlyAndTheCopilotKnowsAboutCartsSegmentsAndRevenue() throws Exception {
        assertThat(ops.check()).isNotNull();
        Shop s = shop();
        order(s, true);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("revenue_sources", Map.of("days", 30)));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("customer_segments", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("abandoned_carts", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("experiments", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/copilot/ask", "{\"question\":\"how are we doing?\"}").andExpect(status().isOk());
        String all = FakeAiProvider.RESULTS.toString();
        assertThat(all).contains("totalRevenueMinor").contains("NEW").contains("waiting").contains("tests");
    }
}
