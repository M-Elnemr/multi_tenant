package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * I-010. A customer who says «عايز X» and then, in another message, «عايز Y» wants BOTH: when the order is prepared with Y alone, the tool sends the model back to include X (or to ask whether X
 * is still wanted). A customer who replaced or dropped X is not asked again, an item is never added by code (the model adds it with its own verified variant), and the guard speaks only once per reply.
 */
class MultiItemOrderIntentIntegrationTest extends IntegrationTestBase {
    @Autowired ChatService chat;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    record Shop(UUID id, String a, String b, String c, String va, String vb, String vc) {}

    String make(Tenant t, String branch, String name, int price) throws Exception {
        String made = onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":%d,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), price * 100, branch))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(made, "$.variants[0].id");
    }

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        String tag = uniq();
        String a = "شنطة مدرسة ولادي " + tag, b = "شنطة كروس صغيرة " + tag, c = "كاب قطن " + tag;
        return new Shop(jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(), a, b, c, make(t, branch, a, 500), make(t, branch, b, 520), make(t, branch, c, 180));
    }

    String say(Shop s, String ext, String text) { return chat.reply(s.id(), "WEB", ext, null, text).text(); }

    Map<String, Object> order(List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("customerName", "محمود", "phone", "01120224441", "governorateCode", "CAI", "area", "مدينة نصر", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0));
        m.put("items", items);
        return m;
    }

    Map<String, Object> item(String variant) { return Map.of("variantId", variant, "quantity", 1); }

    /** The customer asks for two products in two messages, then gives the details; the model prepares the order with the last one only. */
    @Test
    void anOrderPreparedWithOnlyTheLastRequestedProductIsSentBackForTheEarlierOne() throws Exception {
        Shop s = shop();
        String ext = "mi-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.b());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));          // only the last one
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.va()), item(s.vb())))));   // after the tool's answer: both
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        String reply = say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).as(String.valueOf(FakeAiProvider.RESULTS)).contains(s.a());
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
        assertThat(reply).contains(s.a()).contains(s.b());   // the summary the customer sees holds both
        assertThat(reply).contains("1020");   // 500 + 520, priced by the backend
    }

    @Test
    void aProductTheCustomerReplacedOrDroppedIsNeverBroughtBack() throws Exception {
        Shop s = shop();
        String ext = "mi2-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "لا بدلها عايز " + s.b());   // replaced
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    @Test
    void aSingleRequestedProductIsAcceptedAsIs() throws Exception {
        Shop s = shop();
        String ext = "mi3-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.c());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vc())))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    /** The guard speaks once per reply: a model that insists (the customer really wanted only the last one) is not looped. */
    @Test
    void theGuardSpeaksOnlyOncePerReply() throws Exception {
        Shop s = shop();
        String ext = "mi4-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.b());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));   // insists
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(FakeAiProvider.RESULTS.get(0).result().get("error")).isNotNull();
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }

    /** «لا بدلها عايز Y»: the order carrying both X and Y is sent back for X; with Y alone it goes through; a request in the same message as the replacement is still a request. */
    @Test
    void aReplacedProductIsTakenOutOfTheOrderAndTheReplacementIsKept() throws Exception {
        Shop s = shop();
        String ext = "mi5-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "لا بدلها عايز " + s.b());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.va()), item(s.vb())))));   // both: the replaced one is still there
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        String reply = say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).as(String.valueOf(FakeAiProvider.RESULTS)).contains(s.a());
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
        assertThat(reply).contains(s.b()).doesNotContain(s.a());
    }

    /** The replacement is asked in the same message as the word «بدل»: an order that kept the old product and forgot the new one is sent back for the new one too. */
    @Test
    void theRequestInAReplacementMessageIsNotForgotten() throws Exception {
        Shop s = shop();
        String ext = "mi6-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "لا بدلها عايز " + s.b());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vc())))));   // neither of the two
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).as(String.valueOf(FakeAiProvider.RESULTS)).contains(s.b());
    }

    /** «شيل X» by name after two requests: an order still carrying X is sent back; a product removed by name is never forced back. */
    @Test
    void aProductRemovedByNameIsTakenOutAndNeverForcedBack() throws Exception {
        Shop s = shop();
        String ext = "mi7-" + uniq();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "عايز " + s.b());
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("تمام يا فندم"));
        say(s, ext, "شيل " + s.a());
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.va()), item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("prepare_order", order(List.of(item(s.vb())))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ده الملخص"));
        say(s, ext, "محمود 01120224441 القاهرة مدينة نصر كاش");
        assertThat(String.valueOf(FakeAiProvider.RESULTS.get(0).result().get("error"))).as(String.valueOf(FakeAiProvider.RESULTS)).contains(s.a());
        assertThat(FakeAiProvider.RESULTS.get(1).result().get("error")).as(String.valueOf(FakeAiProvider.RESULTS)).isNull();
    }
}
