package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.EvolutionService;
import com.platform.chat.ProductShare;
import com.platform.chat.WaReportService;
import com.platform.commerce.CartRecoveryService;
import com.platform.commerce.SegmentService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** A suspended, cancelled or archived shop does nothing on its own; and the AI shares the pictures and links of the products it suggests. */
class ShopStopsAndSharingIntegrationTest extends IntegrationTestBase {
    static final String SECRET = "evolution-test-secret-0123456789";
    @Autowired JdbcClient jdbc;
    @Autowired EvolutionService evolution;
    @Autowired SegmentService segments;
    @Autowired CartRecoveryService carts;
    @Autowired WaReportService reports;
    @Autowired ProductShare share;
    @Autowired com.platform.platform.TenantPurgeService purge;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeAiProvider.reset(); FakeEvolutionApi.reset(); FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones, String slug, String name) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "Linen Shirt " + uniq();
        String body = """
                {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","brand":"Acme","description":"Nice","variants":[{"sku":"S-%s","priceMinor":45000,"stock":[{"branchId":"%s","quantity":50}]}]}
                """.formatted(name, uniq(), branch);
        String p = ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", body).andExpect(status().isCreated()));
        String method = JsonPath.read(ok(onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-methods", "{\"type\":\"ZONES\",\"name\":\"Delivery\",\"feeMinor\":0}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/shipping-zones", "{\"name\":\"Greater Cairo\",\"governorateCodes\":[\"CAI\",\"GIZ\"],\"feeMinor\":5000,\"codFeeMinor\":0,\"etaMinDays\":1,\"etaMaxDays\":2}").andExpect(status().isOk());
        return new Shop(t, jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), JsonPath.read(p, "$.variants[0].id"), method, JsonPath.read(p, "$.slug"), name);
    }

    String phone() { return "010" + (10_000_000L + System.nanoTime() % 80_000_000L); }

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

    void setStatus(Shop s, String status) { jdbc.sql("UPDATE core.tenants SET status = :s WHERE id = :t").param("s", status).param("t", s.id()).update(); }

    String instanceOf(Shop s) { return jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", s.id()).query(String.class).single(); }

    ResultActions webhook(String raw) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat/evolution/webhook/" + SECRET).header("Host", "platform.test").contentType("application/json").content(raw));
    }

    String incoming(Shop s, String from, String text) { return "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"pushName\":\"Mona\",\"key\":{\"remoteJid\":\"2%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"M%s\"},\"message\":{\"conversation\":\"%s\"}}}".formatted(instanceOf(s), from, uniq(), text); }

    @Test
    void aSuspendedShopSendsNothingAndAnswersNobodyUntilItIsActiveAgain() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        for (int i = 0; i < 2; i++) order(s, phone());
        linkOwnNumber(s);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"Hello {name}\"}").andExpect(status().isOk());
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"sendMode\":\"FREE\"}").andExpect(status().isOk());
        onHost(h, owner, "PUT", "/api/v1/store/ai/evolution/report", "{\"mode\":\"FREE\",\"enabled\":true,\"sendAfter\":\"18:00\"}").andExpect(status().isOk());
        String p = phone();
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana Samir\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), p, s.variant())).andExpect(status().isNoContent());
        jdbc.sql("UPDATE commerce.abandoned_carts SET created_at = now() - interval '30 hours' WHERE tenant_id = :t").param("t", s.id()).update();
        String zone = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", s.id()).query(String.class).single();
        Instant evening = java.time.LocalDate.now(java.time.ZoneId.of(zone)).atTime(23, 0).atZone(java.time.ZoneId.of(zone)).toInstant();

        for (String stopped : new String[]{"SUSPENDED", "CANCELLED", "ARCHIVED"}) {
            setStatus(s, stopped);
            int calls = FakeEvolutionApi.CALLS.size();
            assertThat(evolution.connected(s.id())).isFalse();
            assertThat(evolution.sendFree(s.id(), "201011112222", "x")).isFalse();
            assertThat(segments.runDue()).isEqualTo(0);            // the broadcast waits
            assertThat(carts.runDue(Instant.now())).isEqualTo(0);   // no cart reminder
            assertThat(reports.runDue(evening)).isEqualTo(0);       // no daily report
            FakeAiProvider.lastSystem = "";
            webhook(incoming(s, phone(), "hello")).andExpect(status().isOk());   // nobody is answered, and the AI is not even asked
            assertThat(FakeAiProvider.lastSystem).isEmpty();
            assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(calls);
        }
        assertThat(jdbc.sql("SELECT status FROM commerce.broadcasts WHERE tenant_id = :t").param("t", s.id()).query(String.class).single()).isEqualTo("SENDING");

        // back to normal: everything that was waiting goes on
        setStatus(s, "ACTIVE");
        assertThat(segments.runDue()).isEqualTo(1);
        assertThat(carts.runDue(Instant.now())).isEqualTo(1);
        assertThat(reports.runDue(evening)).isGreaterThanOrEqualTo(1);
        while (segments.runDue() > 0) { /* finish */ }
    }

    @Test
    void aShopThatIsDeletedHasItsWhatsAppLinkRemovedFirst() throws Exception {
        Shop s = shop();
        linkOwnNumber(s);
        String inst = instanceOf(s);
        UUID actor = jdbc.sql("SELECT id FROM core.users LIMIT 1").query(UUID.class).single();
        purge.purge(actor, s.id(), s.t().slug());
        assertThat(FakeEvolutionApi.CALLS.stream().map(c -> c.method() + " " + c.path()).toList()).contains("DELETE /instance/logout/" + inst, "DELETE /instance/delete/" + inst);
        assertThat(jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t").param("t", s.id()).query(Long.class).single()).isEqualTo(0L);
    }

    @Test
    void theAssistantSharesTheLinkOfEverySuggestedProductOnWhatsApp() throws Exception {
        Shop s = shop();
        String p = phone();
        order(s, p);
        linkOwnNumber(s);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("search_products", Map.of("query", "Linen")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندي " + s.name() + " قميص كتان خفيف، تحبي أجهزه؟"));
        webhook(incoming(s, p, "عايزة قميص")).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("in ONE single message").contains("رقم تليفون عليه واتساب").contains("never ask for it as a separate question");   // all missing details are asked together
        var sends = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/send")).toList();
        assertThat(sends.get(0).path()).startsWith("/message/sendText/");
        assertThat(sends.get(0).body().get("text").toString()).contains(s.name());
        var card = sends.get(sends.size() - 1);   // no picture on this product: its link goes alone as text
        assertThat(card.path()).startsWith("/message/sendText/");
        assertThat(card.body().get("text").toString()).contains(s.name()).contains("450").contains("/products/" + s.slug());
    }

    @Test
    void onlyProductsTheAnswerNamesAreSharedAtMostThreeAndAPictureComesWithItsLink() throws Exception {
        Tenant tn = onboard("STORE");
        UUID t = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", tn.slug()).query(UUID.class).single();
        var products = List.<Map<String, Object>>of(
                Map.of("name", "Blue Dress", "slug", "blue-dress", "priceMinor", 120000, "currency", "EGP", "imageUrl", "/api/v1/files/abc/content"),
                Map.of("name", "Red Dress", "slug", "red-dress", "priceMinor", 99950, "currency", "EGP"),
                Map.of("name", "Green Skirt", "slug", "green-skirt", "priceMinor", 5000, "currency", "EGP"),
                Map.of("name", "Black Coat", "slug", "black-coat", "priceMinor", 5000, "currency", "EGP"),
                Map.of("name", "White Hat", "slug", "white-hat", "priceMinor", 5000, "currency", "EGP"));
        var cards = share.pick(t, "I suggest the Blue Dress, the red dress and the Black Coat, and also the White Hat", products);
        assertThat(cards).hasSize(3);                                             // never more than three
        assertThat(cards.get(0).imageUrl()).contains("/api/bff/files/abc/content").contains("variant=medium");
        assertThat(cards.get(0).caption()).contains("Blue Dress").contains("1200").contains("/products/blue-dress");
        assertThat(cards.get(1).imageUrl()).isNull();                               // no picture: the link alone
        assertThat(cards.get(1).caption()).contains("999.50").contains("/products/red-dress");
        assertThat(cards.stream().noneMatch(c -> c.caption().contains("Green Skirt"))).isTrue();   // not mentioned, not sent
        assertThat(share.pick(t, "Nothing fits, sorry", products)).isEmpty();
        assertThat(share.pick(t, "Blue Dress", List.of())).isEmpty();
    }
}
