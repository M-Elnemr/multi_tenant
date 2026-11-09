package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.billing.WalletService;
import com.platform.commerce.SegmentService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** The human-like rules on the WhatsApp own-number route (rotation, content guard, owner chatting, disconnect = stop). Verified against the fake Evolution API only. */
class WhatsAppHumanPolicyIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired SegmentService segments;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeEvolutionApi.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        jdbc.sql("UPDATE billing.wallets SET balance_minor = 1000 WHERE tenant_id = :t").param("t", id).update();
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"Tee %s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":20000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(uniq(), uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(t.host(), t.access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
        return new Shop(t, id, JsonPath.read(p, "$.variants[0].id"), method);
    }

    String order(Shop s) throws Exception {
        String phone = "010" + (10_000_000L + System.nanoTime() % 80_000_000L);
        String body = """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"Mona Hassan","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones(), phone);
        onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated());
        return phone;
    }

    void drain() {
        for (int i = 0; i < 30; i++) {
            jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = NULL WHERE status = 'SENDING'").update();
            segments.runDue();
            if (jdbc.sql("SELECT count(*) FROM commerce.broadcasts WHERE status = 'SENDING'").query(Long.class).single() == 0) break;
        }
    }

    static List<String> texts() { return FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).map(c -> c.body().get("text").toString()).toList(); }

    @Test
    void wordingsRotateAndConsecutiveMessagesDiffer_noCapApplies() throws Exception {
        Shop s = shop();
        for (int i = 0; i < 6; i++) order(s);
        String id = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts",
                "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hi {name}, new arrivals today\",\"variants\":[\"Hello {name}, new things arrived today\",\"{name}, we just got new stock\"]}").andExpect(status().isOk())), "$.id");
        drain();
        List<String> sent = texts();
        assertThat(sent).hasSize(6);
        for (int i = 1; i < sent.size(); i++) assertThat(sent.get(i)).isNotEqualTo(sent.get(i - 1));
        assertThat(sent.stream().map(t -> t.split("\n")[0].replaceAll("[ .]", "").replaceAll("Mona|mona", "")).distinct().count()).isGreaterThanOrEqualTo(3);   // all three wordings were used
        assertThat(wallet.balance(s.id())).isEqualTo(940L);
        assertThat(jdbc.sql("SELECT status FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("DONE");
    }

    @Test
    void aDisconnectedNumberPausesTheBroadcastAtOnce_andAnActiveOwnerChatIsSkipped() throws Exception {
        Shop s = shop();
        String busyPhone = order(s);
        order(s); order(s);
        UUID conv = jdbc.sql("INSERT INTO chat.conversations (tenant_id, channel, external_id, display_name) VALUES (:t, 'WHATSAPP', :e, 'Busy') RETURNING id").param("t", s.id()).param("e", "2" + busyPhone).query(UUID.class).single();
        jdbc.sql("UPDATE chat.conversations SET takeover = TRUE WHERE id = :c").param("c", conv).update();
        String id = JsonPath.read(ok(onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/broadcasts", "{\"segments\":[\"ALL\"],\"platforms\":[\"WHATSAPP\"],\"message\":\"Hello {name} how are you\"}").andExpect(status().isOk())), "$.id");
        jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = NULL WHERE id = :i").param("i", UUID.fromString(id)).update();
        segments.runDue();   // first message goes (or the busy one is skipped, then one goes)
        FakeEvolutionApi.STATE = "close";   // the number gets unlinked: a possible ban or logout
        jdbc.sql("UPDATE chat.evolution_settings SET status = 'DISCONNECTED' WHERE tenant_id = :t").param("t", s.id()).update();
        jdbc.sql("UPDATE commerce.broadcasts SET next_send_at = NULL WHERE id = :i").param("i", UUID.fromString(id)).update();
        segments.runDue();
        assertThat(jdbc.sql("SELECT status || ':' || coalesce(pause_reason, '') FROM commerce.broadcasts WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("PAUSED:WHATSAPP_DISCONNECTED");
        assertThat(texts().size()).isLessThanOrEqualTo(2);
        long skipped = jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND error = 'OWNER_ACTIVE'").param("b", UUID.fromString(id)).query(Long.class).single();
        assertThat(skipped).isLessThanOrEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.broadcast_recipients WHERE broadcast_id = :b AND status = 'SENDING'").param("b", UUID.fromString(id)).query(Long.class).single()).isZero();
    }
}
