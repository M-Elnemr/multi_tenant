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
 * Two defects found in the A/B benchmark, fixed in code and not in the prompt: (1) an order summary with a product the customer never asked for (the seller suggested a side item to reach the
 * minimum order and the model counted a size change as the customer's yes); (2) the reply saying the shop does not have a product it sells and has in stock.
 */
class OrderGuardsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(UUID id, String fries, String falafel, String hotdog) {}

    String variantOf(Tenant t, String branch, String name, int price) throws Exception {
        String p = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"V-%s","priceMinor":%d,"optionValues":{},"stock":[{"branchId":"%s","quantity":9}]}]}
                """.formatted(name, uniq(), price, branch)).andExpect(status().isCreated()));
        return JsonPath.read(p, "$.variants[0].id");
    }

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(id, variantOf(t, branch, "بطاطس بالجبنة", 6500), variantOf(t, branch, "ساندوتش فلافل سوري", 3500), variantOf(t, branch, "هوت دوج", 7500));
    }

    static Map<String, Object> prepare(List<String> variants) {
        return Map.of("items", variants.stream().map(v -> Map.of("variantId", v, "quantity", 1)).toList(), "customerName", "Mido Ali", "phone", "01011112222",
                "governorateCode", "GIZ", "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY");
    }

    @Test
    void anItemTheCustomerNeverAskedForIsSentBackEvenWhenTheSellerOfferedItAndTheCustomerOnlyChangedASize() throws Exception {
        Shop s = shop();
        String ext = "unreq-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("بطاطس بالجبنة بـ ٦٥ جنيه، أقل طلب ١٠٠ جنيه: تحب تضيف ساندوتش فلافل سوري؟"));
        chat.reply(s.id(), "WEB", ext, null, "عايز بطاطس بالجبنة");
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.fries(), s.falafel()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الطلب أقل من الحد الأدنى، تحب تضيف إيه؟"));
        String reply = chat.reply(s.id(), "WEB", ext, null, "معلش خليها صغير").text();
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).contains("never asked for «ساندوتش فلافل سوري»");
        assertThat(reply).doesNotContain("ملخص");
    }

    @Test
    void anItemTheSellerOfferedAndTheCustomerSaidYesToIsAccepted() throws Exception {
        Shop s = shop();
        String ext = "unreq-yes-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("بطاطس بالجبنة بـ ٦٥ جنيه، أقل طلب ١٠٠ جنيه: تحب تضيف ساندوتش فلافل سوري؟"));
        chat.reply(s.id(), "WEB", ext, null, "عايز بطاطس بالجبنة");
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.fries(), s.falafel()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", ext, null, "ايوه ضيفه");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    @Test
    void anItemTheCustomerNamedIsNeverBlocked() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.fries(), s.falafel()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", "named-" + uniq(), null, "عايز بطاطس بالجبنة وساندوتش فلافل");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    @Test
    void aSingleItemTheCustomerOnlyReferredToIsNotBlocked() throws Exception {
        Shop s = shop();
        String ext = "refer-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ساندوتش فلافل سوري بـ ٣٥ جنيه، تحب تطلبه؟"));
        chat.reply(s.id(), "WEB", ext, null, "عندكم ايه خفيف؟");
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", prepare(List.of(s.falafel()))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        chat.reply(s.id(), "WEB", ext, null, "عايز اطلب");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    @Test
    void sayingTheShopLacksAProductItSellsAndHasInStockIsSentBackForARewrite() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("بصراحة يا فندم مالناش هوت دوج عندنا، عندنا بطاطس بالجبنة بـ ٦٥ جنيه."));
        FakeAiProvider.SCRIPT.add(turns -> {
            String told = turns.stream().map(Turn::text).filter(java.util.Objects::nonNull).reduce("", (a, b) -> a + b);
            return Turn.model(told.contains("sells it and has it in stock") ? "عندنا هوت دوج بـ ٧٥ جنيه، تحب أجهزهولك؟" : "WRONG: the problem was not sent");
        });
        assertThat(chat.reply(s.id(), "WEB", "deny-" + uniq(), null, "عايز هوت دوج").text()).contains("هوت دوج بـ ٧٥").doesNotContain("مالناش");
    }

    @Test
    void aDenialOfAnOptionOrOfAProductTheShopDoesNotSellIsLeftAlone() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف مفيش بيتزا عندنا، عندنا هوت دوج بـ ٧٥ جنيه."));
        assertThat(chat.reply(s.id(), "WEB", "deny-ok-" + uniq(), null, "عايز بيتزا").text()).contains("مفيش بيتزا");
    }

    static final String DENIAL = "بصراحة يا فندم مالناش هوت دوج عندنا. عندنا بطاطس بالجبنة بـ ٦٥ جنيه.";

    /** The invariant: a denial of an in-stock product never goes out, whatever happened to the rewrite. */
    @Test
    void aRewriteThatFailsNeverLetsTheDenialOfASoldProductThrough() throws Exception {
        for (String failure : new String[] {"TIMEOUT", "RATE_LIMIT", "SERVER_ERROR", "EMPTY", "MALFORMED", "CONNECTION"}) {
            Shop s = shop();
            FakeAiProvider.reset();
            FakeAiProvider.SCRIPT.add(turns -> Turn.model(DENIAL));
            for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException(failure, failure, null); });
            String text = chat.reply(s.id(), "WEB", "deny-fail-" + uniq(), null, "عايز هوت دوج").text();
            assertThat(text).as(failure).doesNotContain("مالناش").contains("هوت دوج").contains("75").contains("بطاطس بالجبنة");
        }
    }

    @Test
    void aRewriteThatDeniesTheProductAgainIsReplacedByCodeFromTheShopData() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(DENIAL));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف مفيش هوت دوج عندنا النهاردة."));
        String text = chat.reply(s.id(), "WEB", "deny-again-" + uniq(), null, "عايز هوت دوج").text();
        assertThat(text).doesNotContain("مفيش هوت دوج").contains("هوت دوج").contains("75");
        assertThat(text).as("never invents an option the customer did not get").doesNotContain("مقاس M");
    }

    @Test
    void aDenialInAMixedLanguageMessageIsAlsoReplacedByTheShopFact() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry, we don't have هوت دوج. We have cheese fries for 65."));
        for (int i = 0; i < 3; i++) FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("TIMEOUT", "TIMEOUT", null); });
        String text = chat.reply(s.id(), "WEB", "deny-en-" + uniq(), null, "I want هوت دوج please").text();
        assertThat(text).doesNotContain("don't have").contains("هوت دوج").contains("75");
    }

    /** A shop with one sneaker that comes in white and black only, 1250 EGP, sizes 41 and 42. */
    UUID sneakerShop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"سنيكرز كلاسيك","options":[{"name":"اللون","values":["أبيض","أسود"]},{"name":"المقاس","values":["41","42"]}],
                 "variants":[{"sku":"SN-%1$s","priceMinor":125000,"optionValues":{"اللون":"أبيض","المقاس":"42"},"stock":[{"branchId":"%2$s","quantity":5}]},
                             {"sku":"SK-%1$s","priceMinor":125000,"optionValues":{"اللون":"أسود","المقاس":"41"},"stock":[{"branchId":"%2$s","quantity":5}]}]}
                """.formatted(uniq(), branch)).andExpect(status().isCreated()));
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    void rewriteFails() {
        for (int i = 0; i < 4; i++) FakeAiProvider.SCRIPT.add(turns -> { throw new com.platform.chat.AiProvider.AiUnavailableException("TIMEOUT", "TIMEOUT", null); });
    }

    @Test
    void aColourTheProductDoesNotComeInNeverGoesOutWhenTheRewriteFails() throws Exception {
        UUID id = sneakerShop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("السنيكرز الكلاسيك متاح بالأحمر بـ 1250 جنيه."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "crit-colour-" + uniq(), null, "عايز سنيكرز كلاسيك احمر").text();
        assertThat(text).doesNotContain("بالأحمر").contains("سنيكرز كلاسيك").contains("أبيض").contains("أسود").contains("1250");
    }

    @Test
    void anOrderSaidToBeRecordedWhenNoneExistsIsNeverSentEvenWhenTheRewriteFails() throws Exception {
        UUID id = sneakerShop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("سجلت طلبك يا فندم والسنيكرز الكلاسيك هيوصلك بكرة."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "crit-order-" + uniq(), null, "عايز سنيكرز كلاسيك").text();
        assertThat(text).doesNotContain("سجلت طلبك");
    }

    @Test
    void aCancellationSaidToBeDoneWhenNothingWasCancelledIsNeverSent() throws Exception {
        UUID id = sneakerShop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تم إلغاء طلبك يا فندم."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "crit-cancel-" + uniq(), null, "الغي الطلب").text();
        assertThat(text).doesNotContain("تم إلغاء طلبك");
    }

    @Test
    void theOtherWaysOfSayingAnOrderIsRecordedAreCaughtToo() throws Exception {
        for (String claim : new String[] {"الطلب مسجل عندك كاش واستلام من الفرع.", "تم تأكيد طلبك يا فندم.", "طلبك اتأكد وهيوصلك بكرة."}) {
            UUID id = sneakerShop();
            FakeAiProvider.reset();
            FakeAiProvider.SCRIPT.add(turns -> Turn.model(claim));
            rewriteFails();
            String text = chat.reply(id, "WEB", "crit-order2-" + uniq(), null, "عايز سنيكرز كلاسيك").text();
            assertThat(text).as(claim).doesNotContain("مسجل").doesNotContain("تم تأكيد").doesNotContain("اتأكد");
        }
    }

    /** The open-box laptop: a product of its own whose name holds the new laptop's name; denying it is denying an in-stock product. */
    @Test
    void denyingAnOpenBoxProductThatIsAProductOfItsOwnIsReplacedByItsRealData() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        for (String[] p : new String[][] {{"لاب توب زينترا بوك ١٤", "2499900"}, {"لاب توب زينترا بوك ١٤ أوبن بوكس", "2099900"}})
            body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":%s,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                    """.formatted(p[0], uniq(), p[1], branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف يا فندم زينترا بوك ١٤ عندنا جديد بس ومفيش منه أوبن بوكس."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "openbox-" + uniq(), null, "عايز لاب توب زينترا بوك ١٤ أوبن بوكس").text();
        assertThat(text).doesNotContain("مفيش").contains("أوبن بوكس").contains("20999");
    }

    @Test
    void aReplyThatEndsOnAColonWithNoListGetsTheShopsOwnOptions() throws Exception {
        UUID id = sneakerShop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف الأحمر مش متاح.\n\nالمتاح عندنا في سنيكرز كلاسيك:"));
        rewriteFails();
        String text = chat.reply(id, "WEB", "dangling-" + uniq(), null, "عايز سنيكرز كلاسيك احمر").text();
        assertThat(text.stripTrailing()).doesNotEndWith(":");
        assertThat(text).contains("أبيض").contains("أسود").contains("1250");
    }

    @Test
    void aDenialWrittenWithASpaceInTheNegationIsCaughtToo() throws Exception {
        Shop s = shop();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف يا فندم م عندناش هوت دوج، بس عندنا بطاطس بالجبنة بـ ٦٥ جنيه."));
        rewriteFails();
        String text = chat.reply(s.id(), "WEB", "deny-space-" + uniq(), null, "عايز هوت دوج").text();
        assertThat(text).doesNotContain("م عندناش").contains("هوت دوج").contains("75");
    }

    @Test
    void anImplicitExclusionOfAnOpenBoxProductIsReplacedByItsRealData() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        for (String[] p : new String[][] {{"لاب توب زينترا بوك ١٤", "2499900"}, {"لاب توب زينترا بوك ١٤ أوبن بوكس", "2099900"}, {"موبايل نوفا ماكس", "1849900"}})
            body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":%s,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                    """.formatted(p[0], uniq(), p[1], branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("للأسف يا فندم، الأوبن بوكس عندنا متاح في الموبايلات بس. لاب توب زينترا بوك ١٤ بييجي جديد فقط بـ 24999 جنيه."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "openbox2-" + uniq(), null, "عايز لاب توب زينترا بوك ١٤ أوبن بوكس").text();
        assertThat(text).doesNotContain("بييجي جديد فقط").contains("أوبن بوكس").contains("20999");
    }

    /**
     * Seen in production on the final build (1 of 5 samples): the customer writes «الزينترا بوك 14 الأوبن بوكس» (not the product's full name, no «لاب توب»), the reply says the laptop is
     * «متاح جديد فقط». The extra words «أوبن بوكس» make the open-box product the one asked for.
     */
    @Test
    void anOpenBoxProductAskedForBySomeOfItsWordsIsNotAnsweredWithNewOnly() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        for (String[] p : new String[][] {{"لاب توب زينترا بوك 14", "2499900"}, {"لاب توب زينترا بوك 14 أوبن بوكس", "2099900"}, {"لاب توب زينترا ستيودنت 15", "1599900"}})
            body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":%s,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                    """.formatted(p[0], uniq(), p[1], branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم، بس من بيانات المحل حالياً: الزينترا بوك 14 متاح جديد فقط، يبدأ من 24999 جنيه بضمان سنة من الوكيل. لو حابب أرخص في زينترا ستيودنت 15 بـ 15999. تحب أجهزلك واحد؟"));
        rewriteFails();
        String text = chat.reply(id, "WEB", "openbox3-" + uniq(), null, "الزينترا بوك 14 الأوبن بوكس متاح؟").text();
        assertThat(text).doesNotContain("جديد فقط").contains("أوبن بوكس").contains("20999");
    }

    /** Seen in production on the final build: a price written with «بـ» and no currency word («الجديد بـ 23,999», a price no product has) was invisible to the money guard. */
    @Test
    void aPriceWrittenAfterBaWithNoCurrencyWordThatNoProductHasIsNeverSent() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        for (String[] p : new String[][] {{"لاب توب زينترا بوك 14", "2499900"}, {"لاب توب زينترا بوك 14 أوبن بوكس", "2099900"}})
            body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":%s,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                    """.formatted(p[0], uniq(), p[1], branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("لاب توب زينترا بوك 14 الجديد بـ 23,999 يا فندم، والأوبن بوكس بـ 20,999. الفرق 3,000 جنيه."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "barePrice-" + uniq(), null, "والجديد بكام؟").text();
        assertThat(text).doesNotContain("23,999").doesNotContain("23999");   // a safe reply, with the right price or none
    }

    /** Seen on the final build: «عايز زينترا بوك 14 أوبن بوكس» was answered with the list of the NEW laptop's options (and another product's) because the fallback used the products the model had searched. */
    @Test
    void theShopDataFallbackIsAboutTheProductTheCustomerNamedNotTheOnesTheModelSearched() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String tag = uniq(), base = "لاب توب زينترا " + tag, open = base + " أوبن بوكس";
        for (String[] p : new String[][] {{base, "2499900"}, {open, "2099900"}})
            body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":%s,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                    """.formatted(p[0], uniq(), p[1], branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(open + " متاح بـ 20999 جنيه ومتبقي منه 9 قطع بس، والعرض ده لحد آخر النهاردة."));   // a wrong stock: the final gate replaces it with shop data
        rewriteFails();
        String text = chat.reply(id, "WEB", "fallbackAsked-" + uniq(), null, "عايز " + open).text();
        assertThat(text).doesNotContain("9 قطع").doesNotContain("24999");
        assertThat(text).contains(open);
    }

    /** A hedged invented spec («حوالي 6 ساعات») is still an invented spec. */
    @Test
    void anApproximateBatteryLifeThatTheShopNeverWroteIsNotSent() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "لاب توب أورا " + uniq();
        body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"LP-%s","priceMinor":2099900,"optionValues":{},"stock":[{"branchId":"%s","quantity":3}]}]}
                """.formatted(name, uniq(), branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model(name + " بطاريته 50 واط في الساعة بتديك حوالي 6 ساعات شغل عادي. تحب أحجزهولك؟"));
        rewriteFails();
        String text = chat.reply(id, "WEB", "battery-" + uniq(), null, "والبطارية بتقعد قد إيه في " + name + "؟").text();
        assertThat(text).doesNotContain("6 ساعات").doesNotContain("50 واط");
    }

    /** A sandwich (85/110/140) and its meal (145/175) in a new shop; returns the tenant id. */
    private UUID sandwichAndMeal(Tenant t, String sandwich, String meal) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"%1$s","options":[{"name":"الحجم","values":["عادي","كبير","سوبر"]}],
                 "variants":[{"sku":"A1-%2$s","priceMinor":8500,"optionValues":{"الحجم":"عادي"},"stock":[{"branchId":"%3$s","quantity":9}]},
                             {"sku":"A2-%2$s","priceMinor":11000,"optionValues":{"الحجم":"كبير"},"stock":[{"branchId":"%3$s","quantity":9}]},
                             {"sku":"A3-%2$s","priceMinor":14000,"optionValues":{"الحجم":"سوبر"},"stock":[{"branchId":"%3$s","quantity":9}]}]}
                """.formatted(sandwich, uniq(), branch)).andExpect(status().isCreated()));
        body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"%1$s","options":[{"name":"الحجم","values":["عادي","كبير"]}],
                 "variants":[{"sku":"B1-%2$s","priceMinor":14500,"optionValues":{"الحجم":"عادي"},"stock":[{"branchId":"%3$s","quantity":9}]},
                             {"sku":"B2-%2$s","priceMinor":17500,"optionValues":{"الحجم":"كبير"},"stock":[{"branchId":"%3$s","quantity":9}]}]}
                """.formatted(meal, uniq(), branch)).andExpect(status().isCreated()));
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return id;
    }

    /** The sandwich (85/110/140) and its meal (145/175): a line that gives the sandwich's sizes at the meal's prices is never sent. */
    @Test
    void theOptionPricesOfAMealAreNeverGivenForItsSandwichSizes() throws Exception {
        Tenant t = onboard("STORE");
        String tag = uniq();
        String sandwich = "ساندوتش شاورما فراخ " + tag, meal = "وجبة ساندوتش شاورما فراخ " + tag;
        UUID id = sandwichAndMeal(t, sandwich, meal);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندك اختيارين: " + meal + " من 145 جنيه، أو " + sandwich + " لوحده: عادي 145 / كبير 175 / سوبر 140."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "mixed-" + uniq(), null, "عايز " + sandwich).text();
        assertThat(text).doesNotContain("عادي 145").contains("85").contains("110");
    }

    /** Seen in production on the final build: the reply names only the meal («ولو ساندوتش لوحده»), the customer asked for the sandwich, and the sizes carry the meal's prices. */
    @Test
    void theMealsPricesForTheSandwichSizesAreCaughtWhenTheReplyNamesOnlyTheMeal() throws Exception {
        Tenant t = onboard("STORE");
        String tag = uniq();
        String sandwich = "ساندوتش شاورما فراخ " + tag, meal = "وجبة ساندوتش شاورما فراخ " + tag;
        UUID id = sandwichAndMeal(t, sandwich, meal);
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("الكانز بييجي مع " + meal + " بـ 145 جنيه. ولو ساندوتش لوحده، اختار الحجم: عادي 145 / كبير 175 / سوبر 140."));
        rewriteFails();
        String text = chat.reply(id, "WEB", "mixed-" + uniq(), null, "عايز " + sandwich + " كانز").text();
        assertThat(text).doesNotContain("عادي 145").doesNotContain("كبير 175").contains("85").contains("110");
    }
}
