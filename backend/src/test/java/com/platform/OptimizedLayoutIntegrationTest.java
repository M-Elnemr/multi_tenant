package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The request layout: the fixed part of the prompt is the system message and this reply's context (the guest note, the products of the chat with their exact stock) goes in front of
 * the customer's newest message, with no stock counts in the cached catalog.
 */
class OptimizedLayoutIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    String system = "";

    List<List<Turn>> ask(Tenant t, String message) throws Exception {
        List<List<Turn>> seen = new ArrayList<>();
        FakeAiProvider.reset();
        FakeAiProvider.SCRIPT.add(turns -> { seen.add(List.copyOf(turns)); system = FakeAiProvider.lastSystem; return Turn.model("عندنا قميص كتان أبيض بـ 450 جنيه، تحب مقاس إيه؟"); });
        onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(UUID.randomUUID(), message)).andExpect(status().isOk());
        return seen;
    }

    Tenant shop(String... more) throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(ok(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null)), "$[0].id");
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of("قميص كتان أبيض"));
        names.addAll(java.util.List.of(more));
        for (String name : names)
            onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"shirts-tops","audience":"ALL","name":"%s","variants":[{"sku":"L-%s","priceMinor":45000,"stock":[{"branchId":"%s","quantity":7}]}]}
                    """.formatted(name, uniq(), branch)).andExpect(status().isCreated());
        return t;
    }

    String context(List<List<Turn>> seen) {
        var last = seen.get(0).get(seen.get(0).size() - 1);
        return last.text().substring(0, last.text().indexOf("⟦END OF CONTEXT⟧"));
    }

    /** The A/B of 2026-11-24: «لو المقاس طلع صغير ابدله ازاي؟» was answered about a small bag, because «صغير» matched «شنطة كروس صغيرة» and the block called it a product of this chat. */
    @Test
    void aReturnQuestionDoesNotBringAProductThatOnlySharesAWord() throws Exception {
        Tenant t = shop("شنطة كروس صغيرة", "شنطة ظهر");
        String ctx = context(ask(t, "لو المقاس طلع صغير ابدله ازاي؟"));
        assertThat(ctx).doesNotContain("شنطة");
    }

    /** The A/B of 2026-11-24: «الضمان على اللاب توب» got four laptops of six and the reply said the shop has only two kinds; the block now says how many there are and names them all. */
    @Test
    void aQuestionAboutAKindNamesEveryProductOfThatKindAndTheCount() throws Exception {
        String[] laptops = {"لاب توب زينترا بوك 14", "لاب توب زينترا إير 13", "لاب توب زينترا بوك 15 برو", "لاب توب زينترا ستيودنت 15", "لاب توب زينترا كرييتور 16", "لاب توب جيمنج زينترا جيمر 16"};
        String[] all = java.util.Arrays.copyOf(laptops, laptops.length + 1);
        all[laptops.length] = "جراب لاب توب زينترا";
        Tenant t = shop(all);
        String ctx = context(ask(t, "الضمان قد ايه على اللاب توب؟ وبيغطي الكسر؟"));
        assertThat(ctx).contains("WORD MATCHES").contains("6 products of the shop have it in their name").contains("that is all of them").doesNotContain("THE PRODUCTS OF THIS CHAT").doesNotContain("جراب");
        for (String l : laptops) assertThat(ctx).contains(l);
    }

    /** A product the customer names is a product of this chat, with its exact stock, apart from the word matches. */
    @Test
    void aNamedProductIsAProductOfThisChat() throws Exception {
        Tenant t = shop("شنطة كروس صغيرة");
        String ctx = context(ask(t, "قميص كتان أبيض متاح؟"));
        assertThat(ctx).contains("THE PRODUCTS OF THIS CHAT").contains("7 in stock");
        assertThat(ctx.indexOf("قميص كتان أبيض")).isGreaterThan(ctx.indexOf("THE PRODUCTS OF THIS CHAT"));
    }

    @Test
    void theFixedPartIsTheSystemMessageAndTheContextComesWithTheNewestMessage() throws Exception {
        Tenant t = shop();
        var seen = ask(t, "قميص كتان أبيض متاح؟");
        assertThat(system).doesNotContain("The customer is a guest").doesNotContain("7 in stock").contains("WHERE THIS REPLY'S CONTEXT IS").contains("قميص كتان أبيض");
        var last = seen.get(0).get(seen.get(0).size() - 1);
        assertThat(last.text()).startsWith("⟦CONTEXT FOR THIS REPLY").contains("The customer is a guest").contains("PRODUCTS FOR THE CUSTOMER'S NEWEST MESSAGE").contains("7 in stock")
                .endsWith("قميص كتان أبيض متاح؟");
    }
}
