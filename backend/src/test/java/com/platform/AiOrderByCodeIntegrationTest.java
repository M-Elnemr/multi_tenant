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
    void theRoundTenMissesAreSummarisedByCodeToo() throws Exception {
        Shop s = shop();
        // «لا غيرها لـ كبير»: a swap said with «لـ»
        assertThat(change(s, List.of(s.small()), "عايز ساندوتش شاورما عادي", "لا غيرها لـ كبير")).contains("ملخص").contains("110");
        // «شيل X وخلي Y بس» with the model writing its own summary
        String ext = "fake-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.small(), s.pepsi()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", ext, null, "عايز ساندوتش شاورما عادي وبيبسي كانز");
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، تم تعديل الطلب:\n\nالمنتجات: ٨٥ جنيه\nالإجمالي: ١١٠ جنيه\n\nأأكد الأوردر؟ اكتب «تمام»"));
        String r = chat.reply(s.id(), "WEB", ext, null, "شيل بيبسي كانز وخلي ساندوتش شاورما بس").text();
        assertThat(r).contains("ده ملخص").contains("ساندوتش شاورما").doesNotContain("بيبسي");
    }

    @Test
    void aChangeOrderNumberBeforeAnyOrderAndAFlagToTheOwnerNeverBlockTheNewSummary() throws Exception {
        Shop s = shop();
        String ext = "flag-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.small()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", ext, null, "عايز ساندوتش شاورما عادي");
        // round 10: Qwen sent changeOrder «1» while no order existed, was refused, asked the owner, and wrote no summary
        Map<String, Object> withChange = new java.util.HashMap<>(prepare(List.of(s.large())));
        withChange.put("changeOrder", "1");
        withChange.put("dropsEarlierItems", true);
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", withChange));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        String r = chat.reply(s.id(), "WEB", ext, null, "لا غيرها لـ كبير").text();
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result())).doesNotContain("cannot be changed");
        assertThat(r).contains("ملخص").contains("110");
        // a flag to the owner in the same turn does not stop the summary by code
        String ext2 = "flag2-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.small()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", ext2, null, "عايز ساندوتش شاورما عادي");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("flag_unanswered", Map.of("question", "العميل عايز يغير الحجم لكبير")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("هسأل الفريق وأرد عليك"));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("هسأل الفريق وأرد عليك"));
        assertThat(chat.reply(s.id(), "WEB", ext2, null, "خليها كبير بدل عادي").text()).contains("ملخص").contains("110");
    }

    @Test
    void anItemAddedToReachTheMinimumGetsTheFirstSummaryByCode() throws Exception {
        Shop s = shop();
        jdbc.sql("UPDATE commerce.store_profiles SET min_order_minor = 10000 WHERE tenant_id = :t").param("t", s.id()).update();
        String ext = "min-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاح يا فندم بـ ٢٥ جنيه"));
        chat.reply(s.id(), "WEB", ext, null, "عايز بيبسي كانز");
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("أقل قيمة للطلب ١٠٠ جنيه، تحب تضيف إيه؟"));
        String told = chat.reply(s.id(), "WEB", ext, null, "انا كريم، الجيزة الدقي شارع التحرير عمارة ٥ شقة ٢، ٠١٠١٢٣٤٥٦٧٨، كاش").text();
        assertThat(told).doesNotContain("ده ملخص");
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، هضيفه 👌"));
        String r = chat.reply(s.id(), "WEB", ext, null, "وضيف معاه ساندوتش شاورما كبير").text();
        assertThat(r).contains("ده ملخص").contains("بيبسي كانز").contains("ساندوتش شاورما").contains("135");   // 25 + 110
    }

    @Test
    void severalProductsEachKeepTheirOwnOptionsAndGetTheSummaryByCode() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String[] ids = new String[4];
        int i = 0;
        for (String name : List.of("فستان محجبات", "بومبر جاكيت")) {
            String p = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","options":[{"name":"المقاس","values":["XL","XXL"]}],
                     "variants":[{"sku":"A-%s","priceMinor":100000,"optionValues":{"المقاس":"XL"},"stock":[{"branchId":"%s","quantity":9}]},
                                 {"sku":"B-%s","priceMinor":120000,"optionValues":{"المقاس":"XXL"},"stock":[{"branchId":"%s","quantity":9}]}]}
                    """.formatted(name, uniq(), branch, uniq(), branch)).andExpect(status().isCreated()));
            boolean xlFirst = !JsonPath.<String>read(p, "$.variants[0].comboKey").contains("XXL");
            ids[i++] = JsonPath.read(p, xlFirst ? "$.variants[0].id" : "$.variants[1].id");
            ids[i++] = JsonPath.read(p, xlFirst ? "$.variants[1].id" : "$.variants[0].id");
        }
        UUID tenant = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String ext = "multi-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("متاحين يا فندم، ابعتلي بياناتك"));
        chat.reply(tenant, "WEB", ext, null, "عايز فستان محجبات XL وبومبر جاكيت XXL");
        // the model's summary with the jacket in XL is refused (the dress's «XL» is not the jacket's)
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", Map.of("items", List.of(Map.of("variantId", ids[0], "quantity", 1), Map.of("variantId", ids[2], "quantity", 1)),
                "customerName", "كريم", "phone", "01012345678", "governorateCode", "GIZ", "addressLine1", "شارع التحرير عمارة ٥ شقة ٢", "area", "الدقي", "paymentMethod", "CASH_ON_DELIVERY")));
        for (int k = 0; k < 3; k++) FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، هجهزلك الطلب"));
        String r = chat.reply(tenant, "WEB", ext, null, "انا كريم، الجيزة الدقي شارع التحرير عمارة ٥ شقة ٢، ٠١٠١٢٣٤٥٦٧٨، كاش").text();
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result())).contains("XXL");
        // and code makes the right one: the dress in XL, the jacket in XXL
        assertThat(r).contains("ده ملخص").contains("فستان محجبات (المقاس: XL)").contains("بومبر جاكيت (المقاس: XXL)").contains("2200");
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
