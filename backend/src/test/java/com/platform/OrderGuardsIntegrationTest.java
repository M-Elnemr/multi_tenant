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
}
