package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Exam round 9: after a summary the model said «هضيفها» or «عدّلت الطلب» without a new summary, and a pickup order never got one. Code now makes the new summary itself (the customer
 * still confirms), with every lock of prepare_order.
 */
class AiOrderByCodeIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(UUID id, String small, String large, String pepsi) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String sandwich = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"ساندوتش شاورما","options":[{"name":"الحجم","values":["عادي","كبير"]}],
                 "variants":[{"sku":"SW1-%s","priceMinor":8500,"optionValues":{"الحجم":"عادي"},"stock":[{"branchId":"%s","quantity":9}]},
                             {"sku":"SW2-%s","priceMinor":11000,"optionValues":{"الحجم":"كبير"},"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(uniq(), branch, uniq(), branch)).andExpect(status().isCreated()));
        String pepsi = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"بيبسي كانز","variants":[{"sku":"P-%s","priceMinor":2500,"optionValues":{},"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String small = JsonPath.read(sandwich, "$.variants[0].id"), large = JsonPath.read(sandwich, "$.variants[1].id");
        if (JsonPath.<String>read(sandwich, "$.variants[0].comboKey").contains("كبير")) { String x = small; small = large; large = x; }
        return new Shop(id, small, large, JsonPath.read(pepsi, "$.variants[0].id"));
    }

    static Map<String, Object> prepare(List<String> variants) {
        return Map.of("items", variants.stream().map(v -> Map.of("variantId", v, "quantity", 1)).toList(), "customerName", "Mido Ali", "phone", "01011112222",
                "governorateCode", "GIZ", "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY");
    }

    /** A first summary with these items, then a change the model only talks about. */
    String change(Shop s, List<String> first, String firstWords, String changeWords) {
        String ext = "bycode-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(first)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        assertThat(chat.reply(s.id(), "WEB", ext, null, firstWords).text()).contains("ملخص");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، عدّلت الطلب 👌"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، هضيفهالك 👌"));
        return chat.reply(s.id(), "WEB", ext, null, changeWords).text();
    }

    @Test
    void anOptionChangedAfterTheSummaryGetsTheNewSummaryByCode() throws Exception {
        Shop s = shop();
        String r = change(s, List.of(s.small()), "عايز ساندوتش شاورما عادي", "معلش خليها كبير بدل عادي");
        assertThat(r).contains("ملخص").contains("كبير").contains("110").doesNotContain("85 ");
    }

    @Test
    void anItemAddedAfterTheSummaryGetsTheNewSummaryByCode() throws Exception {
        Shop s = shop();
        String r = change(s, List.of(s.small()), "عايز ساندوتش شاورما عادي", "وضيف معاه بيبسي كانز");
        assertThat(r).contains("ملخص").contains("ساندوتش شاورما").contains("بيبسي كانز").contains("110");   // 85 + 25
    }

    @Test
    void aNewQuantityAfterTheSummaryGetsTheNewSummaryByCode() throws Exception {
        Shop s = shop();
        String r = change(s, List.of(s.small()), "عايز ساندوتش شاورما عادي", "خليهم ٢");
        assertThat(r).contains("ملخص").contains("× 2").contains("170");
    }

    @Test
    void anItemTakenOutAfterTheSummaryGetsTheNewSummaryByCode() throws Exception {
        Shop s = shop();
        String r = change(s, List.of(s.small(), s.pepsi()), "عايز ساندوتش شاورما عادي وبيبسي كانز", "شيل البيبسي كانز وخلي الساندوتش بس");
        assertThat(r).contains("ملخص").contains("ساندوتش شاورما").doesNotContain("بيبسي");
    }

    @Test
    void aPickupOrderWithNameAndMobileGetsItsSummaryByCode() throws Exception {
        Shop s = shop();
        jdbc.sql("INSERT INTO commerce.shipping_methods (tenant_id, type, name) VALUES (:t, 'PICKUP', 'استلام من الفرع')").param("t", s.id()).update();
        String ext = "pickup-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح يا فندم بـ ١١٠ جنيه، ابعتلي اسمك ورقمك"));
        chat.reply(s.id(), "WEB", ext, null, "عايز ساندوتش شاورما كبير");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا أستاذ كريم، تحب تضيف حاجة تانية؟"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا أستاذ كريم، تحب تضيف حاجة تانية؟"));
        String r = chat.reply(s.id(), "WEB", ext, null, "انا كريم ٠١٠١٢٣٤٥٦٧٨، هستلم من الفرع بنفسي، كاش").text();
        assertThat(r).contains("ملخص").contains("الاستلام من المحل").contains("110");
    }
}
