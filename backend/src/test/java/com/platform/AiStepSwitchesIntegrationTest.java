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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The admin's test switches «fewer AI steps» (fast_path) and «short history» (short_history): what each changes, and that off is the behaviour as before. */
class AiStepSwitchesIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @AfterEach
    void off() { set("fast_path", null); set("short_history", null); }

    void set(String column, String v) {
        jdbc.sql("INSERT INTO chat.platform_ai_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING").update();
        jdbc.sql("UPDATE chat.platform_ai_config SET " + column + " = :v WHERE id = 1").param("v", v).update();
        com.platform.chat.AiConfigService.forgetCachedForTests();
    }

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
    void fewerStepsEndsAtTheSummaryAndAtAFullAnswerNextToTheCardsAndSaysSoInThePrompt() throws Exception {
        Tenant t = onboard("STORE");
        String[] mug = mug(t);
        String[] reply = new String[1];
        // off: as before, the model is asked again after both tools
        assertThat(orderCalls(t, mug[2])).isEqualTo(2);
        assertThat(cardCalls(t, mug[1], mug[0], reply)).isEqualTo(2);
        assertThat(FakeAiProvider.lastRawSystem).doesNotContain("FEWER LOOKUPS");
        // on: no call whose words are thrown away
        set("fast_path", "on");
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
        // the rule reaches the model
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello!"));
        send(t, UUID.randomUUID().toString(), "hello there friend");
        assertThat(FakeAiProvider.lastRawSystem).contains("FEWER LOOKUPS");
    }

    @Test
    void shortHistorySendsTheRecentMessagesAndTheEarlierChatFromTheDatabase() throws Exception {
        Tenant t = onboard("STORE");
        String[] mug = mug(t);
        String conv = UUID.randomUUID().toString();
        int[] sent = new int[1];
        // a long chat: the size and the product are said at the start
        send(t, conv, "my name is Karim, my size is XL and I like the " + mug[0]);
        for (int i = 1; i <= 9; i++) send(t, conv, "question number " + i + " about delivery times please");
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { sent[0] = turns.size(); return Turn.model("ok"); });
        send(t, conv, "and what about returns please");
        int all = sent[0];
        assertThat(all).as("off: the whole window").isGreaterThanOrEqualTo(21);
        assertThat(FakeAiProvider.lastSystem).doesNotContain("EARLIER IN THIS CHAT");
        // on: only the recent part, and what the customer said before it from the database
        set("short_history", "on");
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { sent[0] = turns.size(); return Turn.model("ok"); });
        send(t, conv, "and what about exchanges please");
        assertThat(sent[0]).isLessThan(all).isLessThanOrEqualTo(com.platform.chat.ChatService.SHORT_KEEP + com.platform.chat.ChatService.SHORT_STEP + 1);
        assertThat(FakeAiProvider.lastSystem).contains("EARLIER IN THIS CHAT").contains("my size is XL")
                .contains("THE CHAT BEFORE THE MESSAGES YOU SEE").contains(mug[0]);
    }
}
