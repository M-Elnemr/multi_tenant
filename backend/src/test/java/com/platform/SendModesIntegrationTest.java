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

/** Broadcasts through the own number and app notifications; order confirmations and cart reminders go only from the own number (0.1 EGP each). */
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
    void confirmationsAndCartRemindersGoOnlyFromTheOwnNumberAndCostPointOneEgp() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        onHost(h, owner, "GET", "/api/v1/store/ai/whatsapp", null).andExpect(jsonPath("$.settings.sendMode").value("FREE"));
        // the old paid mode is ignored: it is always FREE (the own number) and the paid switch of carts is always off
        onHost(h, owner, "PUT", "/api/v1/store/ai/whatsapp", "{\"sendMode\":\"PAID\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendMode").value("FREE"));
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"allowPaid\":true,\"sendMode\":\"PAID\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.sendMode").value("FREE")).andExpect(jsonPath("$.allowPaid").value(false)).andExpect(jsonPath("$.messagePriceMinor").value(10));
        wallet.credit(s.id(), 1000, "TOPUP", null, "test", null);

        String phone = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Nada Ali\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), phone, s.variant())).andExpect(status().isNoContent());
        String cartId = JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[0].id");

        // no own number: nothing is sent and nothing is charged, and the cart says why
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_OWN_NUMBER"));
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(1000L);
        assertThat(jdbc.sql("SELECT reminder_status FROM commerce.abandoned_carts WHERE id = CAST(:i AS uuid)").param("i", cartId).query(String.class).single()).isEqualTo("NO_OWN_NUMBER");

        linkOwnNumber(s);
        FakeMetaGraph.SENT.clear();
        int before = FakeEvolutionApi.CALLS.size();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isOk());
        var sent = FakeEvolutionApi.CALLS.subList(before, FakeEvolutionApi.CALLS.size()).stream().filter(c -> c.path().startsWith("/message/sendText/")).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body().get("text").toString()).contains("Nada").contains("/cart");
        assertThat(FakeMetaGraph.SENT).isEmpty();
        assertThat(wallet.balance(s.id())).isEqualTo(990L);   // 0.1 EGP, charged once
        // the reminder is in the customer's chat as a CART message
        assertThat(jdbc.sql("SELECT count(*) FROM chat.messages m JOIN chat.conversations c ON c.id = m.conversation_id WHERE c.tenant_id = :t AND c.channel = 'WHATSAPP' AND m.kind = 'CART'").param("t", s.id()).query(Long.class).single()).isEqualTo(1L);

        // a send that fails gives the 0.1 EGP back
        String p2 = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), p2, s.variant())).andExpect(status().isNoContent());
        String cart2 = ((java.util.List<?>) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[?(@.phone=='+2" + p2 + "')].id")).get(0).toString();
        FakeEvolutionApi.FAIL_SEND = true;
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SEND_FAILED"));
        assertThat(wallet.balance(s.id())).isEqualTo(990L);
        FakeEvolutionApi.FAIL_SEND = false;

        // no credit: nothing is sent
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 5 WHERE tenant_id = :t").param("t", s.id()).update();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cart2 + "/remind", "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CREDIT"));
        assertThat(FakeMetaGraph.SENT).isEmpty();
        onHost(h, owner, "POST", "/api/v1/store/carts/template", null).andExpect(status().isNotFound());   // the template endpoint is gone
    }

}
