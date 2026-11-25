package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.ToolCall;
import com.platform.chat.AiProvider.Turn;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Fewer AI steps (always on since the 2026-11-25 A/B): no model call whose words would be thrown away, and the rule to answer from the products already listed. */
class FewerAiStepsIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }


    String body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String[] mug(Tenant t) throws Exception {
        String branch = JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        String name = "Mug " + uniq();
        String created = body(onHost(t.host(), t.access(), "POST", "/api/v1/store/products",
                "{\"taxonomySlug\":\"cookware\",\"name\":\"%s\",\"variants\":[{\"sku\":\"M-%s\",\"priceMinor\":10000,\"optionValues\":{},\"stock\":[{\"branchId\":\"%s\",\"quantity\":9}]}]}".formatted(name, uniq(), branch)).andExpect(status().isCreated()));
        return new String[]{name, JsonPath.read(created, "$.slug"), JsonPath.read(created, "$.variants[0].id")};
    }

    Map<String, Object> prepare(String variant) {
        return Map.of("items", List.of(Map.of("variantId", variant, "quantity", 1)), "customerName", "Nada Ali", "phone", "01011112222", "governorateCode", "CAI",
                "addressLine1", "5 Tahrir St, floor 2", "area", "Dokki", "paymentMethod", "CASH_ON_DELIVERY", "discountPercent", 0);
    }

    String send(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    /** Calls to the model of the order case: the summary code writes replaces the model's words, so with the switch nothing is asked after it. */
    int orderCalls(Tenant t, String variant) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return FakeAiProvider.call("prepare_order", prepare(variant)); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("SECOND CALL"); });
        String reply = JsonPath.read(send(t, UUID.randomUUID().toString(), "one mug please"), "$.reply");
        assertThat(reply).contains("Items: 100 EGP").doesNotContain("SECOND CALL");
        return calls.get();
    }

    /** Calls of the pictures case: a full answer written next to show_products is the reply with the switch. */
    int cardCalls(Tenant t, String slug, String name, String[] reply) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return new Turn("model", "Here is our " + name + ", it costs 100 EGP and it is in stock.", List.of(new ToolCall("show_products", Map.of("slugs", List.of(slug)))), List.of(), null); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("Would you like to order it?"); });
        reply[0] = JsonPath.read(send(t, UUID.randomUUID().toString(), "show me a picture of the " + name), "$.reply");
        return calls.get();
    }

    @Test
    void theReplyEndsAtTheSummaryAndAtAFullAnswerNextToTheCardsAndThePromptSaysSo() throws Exception {
        Tenant t = onboard("STORE");
        String[] mug = mug(t);
        String[] reply = new String[1];
        assertThat(orderCalls(t, mug[2])).as("the summary is written by code").isEqualTo(1);
        assertThat(cardCalls(t, mug[1], mug[0], reply)).as("the answer next to the cards is complete").isEqualTo(1);
        assertThat(reply[0]).contains("Here is our " + mug[0]).contains("100");
        // a promise to look is no answer: the model is asked again
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return new Turn("model", "One moment, let me check that for you please.", List.of(new ToolCall("show_products", Map.of("slugs", List.of(mug[1])))), List.of(), null); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("It is " + mug[0] + " for 100 EGP, in stock."); });
        send(t, UUID.randomUUID().toString(), "do you have the " + mug[0]);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(FakeAiProvider.lastRawSystem).contains("FEWER LOOKUPS");
    }
}
