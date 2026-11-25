package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.ToolCall;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A size the shop delivers only to some governorates (TVs of 50 inches and up by its own truck to Cairo and Giza): the owner sets it on the product, the model sees it next to the product,
 * prepare_order refuses another governorate for it (the 43-inch goes anywhere); and the answer the model writes next to a hand-off to the shop team is no longer lost.
 */
class DeliveryLimitIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    record Shop(Tenant t, UUID id, String product, String small, String big) {}

    Shop shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String tv = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                {"taxonomySlug":"cookware","name":"تلفزيون فيستا سمارت","options":[{"name":"المقاس","values":["43 بوصة","65 بوصة"]}],
                 "variants":[{"sku":"T43-%s","priceMinor":1599900,"optionValues":{"المقاس":"43 بوصة"},"stock":[{"branchId":"%s","quantity":5}]},
                             {"sku":"T65-%s","priceMinor":2749900,"optionValues":{"المقاس":"65 بوصة"},"stock":[{"branchId":"%s","quantity":5}]}]}
                """.formatted(uniq(), branch, uniq(), branch)).andExpect(status().isCreated()));
        String small = JsonPath.read(tv, "$.variants[0].id"), big = JsonPath.read(tv, "$.variants[1].id");
        if (JsonPath.<String>read(tv, "$.variants[0].comboKey").contains("65")) { String x = small; small = big; big = x; }
        UUID id = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        return new Shop(t, id, JsonPath.read(tv, "$.id"), small, big);
    }

    void limit(Shop s, String json) throws Exception {
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/products/" + s.product() + "/extras", json).andExpect(status().isOk());
    }

    @Test
    void theOwnerLimitsOneSizeAndTheProductSaysSo() throws Exception {
        Shop s = shop();
        limit(s, "{\"deliveryOnly\":{\"areas\":[\"CAI\",\"GIZ\"],\"variantIds\":[\"" + s.big() + "\"]}}");
        String p = body(onHost(s.t().host(), s.t().access(), "GET", "/api/v1/store/products/" + s.product(), null));
        assertThat(JsonPath.<List<String>>read(p, "$.variants[?(@.id=='" + s.big() + "')].deliveryOnly")).containsExactly("CAI,GIZ");
        assertThat(JsonPath.<List<Object>>read(p, "$.variants[?(@.id=='" + s.small() + "')].deliveryOnly")).containsExactly((Object) null);
        // an unknown code is refused, an empty list lifts the limit
        onHost(s.t().host(), s.t().access(), "PATCH", "/api/v1/store/products/" + s.product() + "/extras", "{\"deliveryOnly\":{\"areas\":[\"XXX\"]}}").andExpect(status().isBadRequest());
        limit(s, "{\"deliveryOnly\":{\"areas\":[]}}");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.product_variants WHERE product_id = CAST(:p AS uuid) AND delivery_only IS NOT NULL").param("p", s.product()).query(Long.class).single()).isZero();
    }

    @Test
    void theModelSeesTheLimitAndPrepareOrderRefusesAnotherGovernorate() throws Exception {
        Shop s = shop();
        limit(s, "{\"deliveryOnly\":{\"areas\":[\"CAI\",\"GIZ\"],\"variantIds\":[\"" + s.big() + "\"]}}");
        String ext = "dlim-" + uniq();
        AtomicReference<String> toolResult = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>();
        Map<String, Object> order = Map.of("items", List.of(Map.of("variantId", s.big(), "quantity", 1)), "customerName", "Mido", "phone", "01011112222",
                "governorateCode", "ALX", "addressLine1", "5 Fouad St, Raml", "area", "Raml", "paymentMethod", "CASH_ON_DELIVERY");
        FakeAiProvider.SCRIPT.add(turns -> { seen.set(FakeAiProvider.lastSystem + turns); return FakeAiProvider.call("prepare_order", order); });
        FakeAiProvider.SCRIPT.add(turns -> { toolResult.set(String.valueOf(turns.get(turns.size() - 1).results())); return Turn.model("للأسف المقاس ده بيتوصل للقاهرة والجيزة بس يا فندم."); });
        chat.reply(s.id(), "WEB", ext, null, "عايز تلفزيون فيستا سمارت 65 بوصة، انا ميدو 01011112222 اسكندرية الرمل شارع فؤاد ٥ كاش");
        assertThat(seen.get()).contains("«المقاس: 65 بوصة» are DELIVERED ONLY TO Cairo").doesNotContain("43 بوصة» are DELIVERED");
        assertThat(toolResult.get()).contains("delivered ONLY to").contains("ALX");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.orders WHERE tenant_id = :t").param("t", s.id()).query(Long.class).single()).isZero();
    }

    @Test
    void theAnswerNextToAHandOffReachesTheCustomer() throws Exception {
        Shop s = shop();
        String answer = "سؤال مهم يا فندم، سألت فريق المحل عن تفاصيل الضمان ضد الكسر وهيردوا عليك هنا في نفس الشات.";
        FakeAiProvider.SCRIPT.add(turns -> new Turn("model", answer, List.of(new ToolCall("flag_unanswered", Map.of("question", "هل الضمان بيغطي الكسر؟"))), List.of(), null));
        String reply = chat.reply(s.id(), "WEB", "handoff-" + uniq(), null, "التلفزيون عليه ضمان؟ وبيغطي الكسر؟").text();
        assertThat(reply).contains("سألت فريق المحل عن تفاصيل الضمان ضد الكسر");   // a full answer next to the hand-off is the reply: no second call writes it again
    }
}
