package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.WaReportService;
import com.platform.commerce.GenderService;
import com.platform.commerce.SegmentService;
import com.platform.shared.Gender;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Speaking to each customer as a man or a woman, and the daily report sent free from the shop's own number to itself. */
class GenderAndReportIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired GenderService genders;
    @Autowired SegmentService segments;
    @Autowired WaReportService reports;

    @BeforeEach
    void clean() { jdbc.sql("UPDATE commerce.broadcasts SET status = 'CANCELED' WHERE status IN ('SENDING', 'PAUSED')").update(); FakeAiProvider.reset(); FakeEvolutionApi.reset(); FakeMetaGraph.reset(); }

    String ok(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String variant, String zones) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
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

    void order(Shop s, String name, String phone) throws Exception {
        String body = """
                {"items":[{"variantId":"%s","quantity":1}],"shippingMethodId":"%s","paymentMethod":"CASH_ON_DELIVERY",
                 "address":{"recipientName":"%s","phone":"%s","addressLine1":"12 Tahrir Street, Dokki, floor 3","governorateCode":"CAI","area":"Dokki","landmark":"Next to the bank"}}
                """.formatted(s.variant(), s.zones(), name, phone);
        onHost(s.t().host(), null, "POST", "/api/v1/shop/checkout", body, "k-" + uniq()).andExpect(status().isCreated());
    }

    void linkOwnNumber(Shop s) throws Exception {
        onHost(s.t().host(), s.t().access(), "POST", "/api/v1/store/ai/evolution/enable", "{\"acceptedRisk\":true}").andExpect(status().isOk());
        FakeEvolutionApi.STATE = "open";
        onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/ai/evolution", null).andExpect(jsonPath("$.status").value("CONNECTED"));
    }

    @Test
    void namesTellTheGenderAndPairsPickTheRightForm() {
        assertThat(GenderService.guess("منى حسن")).isEqualTo("FEMALE");
        assertThat(GenderService.guess("Sara Ahmed")).isEqualTo("FEMALE");
        assertThat(GenderService.guess("فاطمة الزهراء")).isEqualTo("FEMALE");
        assertThat(GenderService.guess("هبه محمد")).isEqualTo("FEMALE");   // spelling variants fold together
        assertThat(GenderService.guess("حمزة علي")).isEqualTo("MALE");     // a man's name that ends like a woman's is not misread
        assertThat(GenderService.guess("Osama")).isEqualTo("MALE");
        assertThat(GenderService.guess("محمد أحمد")).isNull();             // unknown is never claimed
        assertThat(GenderService.guess(null)).isNull();
        assertThat(Gender.FEMALE.apply("أهلاً {بيك|بيكي} {name}")).isEqualTo("أهلاً بيكي {name}");
        assertThat(Gender.MALE.apply("أهلاً {بيك|بيكي}")).isEqualTo("أهلاً بيك");
        assertThat(Gender.UNKNOWN.apply("{تحب|تحبي} تكمل؟")).isEqualTo("تحب تكمل؟");
        assertThat(Gender.FEMALE.apply("no pairs {name} here")).isEqualTo("no pairs {name} here");
    }

    @Test
    void theGenderIsGuessedFromTheNameThenTheOwnerAndTheCustomerOverrideIt() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String p = phone();
        order(s, "Mona Hassan", p);
        String list = ok(onHost(h, owner, "GET", "/api/v1/store/customers", null).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(list, "$.data[0].gender")).isEqualTo("FEMALE");
        assertThat((String) JsonPath.read(list, "$.data[0].genderSource")).isEqualTo("AI");
        String id = JsonPath.read(list, "$.data[0].id");
        onHost(h, owner, "PUT", "/api/v1/store/customers/" + id + "/gender", "{\"gender\":\"MALE\"}").andExpect(status().isOk());
        assertThat(genders.forPhone(s.id(), "+2" + p)).isEqualTo(Gender.MALE);
        assertThat(jdbc.sql("SELECT gender_source FROM commerce.customers WHERE id = :i").param("i", UUID.fromString(id)).query(String.class).single()).isEqualTo("OWNER");
        onHost(h, owner, "PUT", "/api/v1/store/customers/" + id + "/gender", "{\"gender\":\"ROBOT\"}").andExpect(status().isBadRequest());
        order(s, "Mona Hassan", p);   // a new order does not undo the owner's choice
        assertThat(genders.forPhone(s.id(), "+2" + p)).isEqualTo(Gender.MALE);
        onHost(h, owner, "PUT", "/api/v1/store/customers/" + id + "/gender", "{\"gender\":\"\"}").andExpect(status().isOk());   // cleared: back to the guess
        assertThat(genders.forPhone(s.id(), "+2" + p)).isEqualTo(Gender.FEMALE);
        // the customer's own pick wins over everyone, and another shop's owner cannot touch this customer
        UUID account = jdbc.sql("INSERT INTO commerce.client_accounts (google_sub, name) VALUES (:g, 'Mona') RETURNING id").param("g", "sub-" + uniq()).query(UUID.class).single();
        jdbc.sql("UPDATE commerce.customers SET user_id = :u WHERE id = :i").param("u", account).param("i", UUID.fromString(id)).update();
        genders.setSelf(account, "MALE");
        assertThat(genders.forPhone(s.id(), "+2" + p)).isEqualTo(Gender.MALE);
        onHost(h, owner, "PUT", "/api/v1/store/customers/" + id + "/gender", "{\"gender\":\"FEMALE\"}").andExpect(status().isOk());
        assertThat(genders.forPhone(s.id(), "+2" + p)).isEqualTo(Gender.MALE);   // the owner cannot override the customer's own choice
        Shop other = shop();
        onHost(other.t().host(), other.t().access(), "PUT", "/api/v1/store/customers/" + id + "/gender", "{\"gender\":\"FEMALE\"}").andExpect(status().isNotFound());
    }

    @Test
    void broadcastsAndRemindersSpeakToEachCustomerInTheirOwnForm() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        order(s, "Mona Hassan", phone());
        order(s, "Omar Nabil", phone());
        jdbc.sql("UPDATE commerce.customers SET gender = 'MALE', gender_source = 'OWNER' WHERE tenant_id = :t AND name = 'Omar Nabil'").param("t", s.id()).update();
        linkOwnNumber(s);
        onHost(h, owner, "POST", "/api/v1/store/broadcasts", "{\"segment\":\"ALL\",\"mode\":\"FREE\",\"message\":\"أهلاً {بيك|بيكي} يا {name}\"}").andExpect(status().isOk());
        while (segments.runDue() > 0) { /* send them all */ }
        var texts = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).map(c -> c.body().get("text").toString()).toList();
        assertThat(texts).hasSize(2);
        assertThat(texts.stream().anyMatch(x -> x.startsWith("أهلاً بيكي يا Mona"))).isTrue();
        assertThat(texts.stream().anyMatch(x -> x.startsWith("أهلاً بيك يا Omar"))).isTrue();

        // a cart reminder to a woman says تحبي / معاكي
        onHost(h, owner, "PUT", "/api/v1/store/carts/settings", "{\"recoveryEnabled\":true,\"sendMode\":\"FREE\"}").andExpect(status().isOk());
        String phone = phone();
        order(s, "Hana Samir", phone);
        onHost(h, null, "POST", "/api/v1/shop/carts/snapshot", "{\"clientKey\":\"k%s\",\"name\":\"Hana Samir\",\"phone\":\"%s\",\"items\":[{\"variantId\":\"%s\",\"quantity\":1}]}".formatted(uniq(), phone, s.variant())).andExpect(status().isNoContent());
        String cartId = ((java.util.List<?>) JsonPath.read(ok(onHost(h, owner, "GET", "/api/v1/store/carts?view=all", null)), "$.carts[?(@.phone=='+2" + phone + "')].id")).get(0).toString();
        FakeEvolutionApi.CALLS.clear();
        onHost(h, owner, "POST", "/api/v1/store/carts/" + cartId + "/remind", "{}").andExpect(status().isOk());
        String reminder = FakeEvolutionApi.CALLS.stream().filter(c -> c.path().startsWith("/message/sendText/")).findFirst().orElseThrow().body().get("text").toString();
        assertThat(reminder).contains("تحبي").contains("معاكي").doesNotContain("{");
    }

    @Test
    void theAssistantKnowsWhoItIsTalkingToAndRepliesToBroadcastRecipients() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        String p = phone();
        order(s, "Mona Hassan", p);
        linkOwnNumber(s);
        String inst = jdbc.sql("SELECT instance FROM chat.evolution_settings WHERE tenant_id = :t").param("t", s.id()).query(String.class).single();
        FakeAiProvider.SCRIPT.add(turns -> com.platform.chat.AiProvider.Turn.model("أهلاً بيكي"));
        String msg = "{\"event\":\"messages.upsert\",\"instance\":\"%s\",\"data\":{\"pushName\":\"Mona\",\"key\":{\"remoteJid\":\"2%s@s.whatsapp.net\",\"fromMe\":false,\"id\":\"R%s\"},\"message\":{\"conversation\":\"hello I need a dress\"}}}".formatted(inst, p, uniq());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chat/evolution/webhook/evolution-test-secret-0123456789").header("Host", "platform.test").contentType("application/json").content(msg)).andExpect(status().isOk());
        assertThat(FakeAiProvider.lastSystem).contains("WOMAN").contains("بيكي");
        assertThat(FakeEvolutionApi.CALLS.stream().anyMatch(c -> c.path().startsWith("/message/sendText/") && c.body().get("text").toString().contains("أهلاً بيكي"))).isTrue();
    }

    @Test
    void theDailyReportGoesFreeFromTheShopsOwnNumberToItself() throws Exception {
        Shop s = shop();
        String h = s.t().host(), owner = s.t().access();
        order(s, "Mona Hassan", phone());
        onHost(h, owner, "PUT", "/api/v1/store/ai/evolution/report", "{\"mode\":\"FREE\",\"enabled\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVOLUTION_OFF"));
        linkOwnNumber(s);
        onHost(h, owner, "GET", "/api/v1/store/ai/evolution/report", null).andExpect(jsonPath("$.mode").value("PAID")).andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.phone").value("201012345678"));
        onHost(h, owner, "PUT", "/api/v1/store/ai/evolution/report", "{\"mode\":\"FREE\",\"enabled\":true,\"sendAfter\":\"21:30\",\"language\":\"ar\"}").andExpect(status().isOk()).andExpect(jsonPath("$.sendAfter").value("21:30")).andExpect(jsonPath("$.enabled").value(true));
        onHost(h, owner, "PUT", "/api/v1/store/ai/evolution/report", "{\"sendAfter\":\"09:00\"}").andExpect(status().isBadRequest());

        onHost(h, owner, "POST", "/api/v1/store/ai/evolution/report/send-now", "{}").andExpect(status().isOk());
        var now = FakeEvolutionApi.CALLS.get(FakeEvolutionApi.CALLS.size() - 1);
        assertThat(now.path()).startsWith("/message/sendText/");
        assertThat(now.body().get("number")).isEqualTo("201012345678");   // to itself
        assertThat(now.body().get("text").toString()).isNotBlank();

        // the nightly job sends it once after the chosen time, and never through the platform number
        String zone = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", s.id()).query(String.class).single();
        java.time.Instant evening = LocalDate.now(ZoneId.of(zone)).atTime(22, 0).atZone(ZoneId.of(zone)).toInstant();
        int before = FakeEvolutionApi.CALLS.size();
        assertThat(reports.runDue(evening)).isGreaterThanOrEqualTo(1);
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(before + 1);
        assertThat(reports.runDue(evening)).isEqualTo(0);   // already sent for today
        assertThat(FakeEvolutionApi.CALLS.size()).isEqualTo(before + 1);
        assertThat(FakeMetaGraph.SENT).isEmpty();
        onHost(h, null, "GET", "/api/v1/store/ai/evolution/report", null).andExpect(status().isUnauthorized());
    }
}
