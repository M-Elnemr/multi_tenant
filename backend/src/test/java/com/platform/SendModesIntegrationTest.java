package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.commerce.SegmentService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Broadcasts to every customer or one chip: free (own number), paid (official, 3 EGP each) and app notification; and free or paid for order confirmations and cart reminders. */
class SendModesIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired SegmentService segments;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeAiProvider.reset(); FakeHttpGateway.reset(); FakeMetaGraph.reset(); FakeEvolutionApi.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 0 WHERE tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)").param("s", t.slug()).update();   // start without the 100 free points
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), JsonPath.read(p, "$.variants[0].id"), method);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

    void order(Shop s, String phone, int unused) throws Exception { order(s, phone); }

    void order(Shop s, String phone) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones(), phone);
        onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated());
    }

    void linkOwnNumber(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void confirmationsAndCartRemindersChooseFreeOrPaidAndFreeNeedsTheLinkedNumber() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"FREE\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"sendMode\":\"FREE\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        onHost(h, owner, "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.settings.sendMode").value("PAID"));
        linkOwnNumber(s);
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"FREE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"sendMode\":\"FREE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));

        // a cart reminder now goes from the shop's own number, with no official message and no charge
        String phone = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Nada Ali\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), phone, s.variant())).andExpect(status().isNoContent());
        String cartId = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[0].id");
        FakeMetaGraph.SENT.clear();
        int before = FakeEvolutionApi.CALLS.size();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isOk());
        var sent = FakeEvolutionApi.CALLS.subList(before, FakeEvolutionApi.CALLS.size()).stream().filter(c -> c.path().startsWith("/message/sendText/")).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body().get("text").toString()).contains("Nada").contains("/cart");
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(0L);

        // when the phone is unlinked again, a FREE shop is never silently moved to the paid route
        FakeEvolutionApi.STATE = "close";
        onHost(h, owner, "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("DISCONNECTED"));
        String p2 = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), p2, s.variant())).andExpect(status().isNoContent());
        String cart2 = ((java.util.List<?>) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[?(@.phone=='+2" + p2 + "')].id")).get(0).toString();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict());
        assertThat(FakeMetaGraph.SENT).isEmpty();
    }

}
