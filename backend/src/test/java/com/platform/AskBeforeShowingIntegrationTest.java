package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** «عايز موبايل» answered with only «ميزانيتك كام؟» (the short prompt, 2026-11-24): a request for a kind of item the shop sells gets products, then one question. */
class AskBeforeShowingIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    UUID shop() throws Exception {
        Tenant t = onboard("STORE");
        String branch = JsonPath.read(onHost(t.host(), t.access(), "GET", "/api/v1/store/branches", null).andReturn().getResponse().getContentAsString(), "$[0].id");
        for (String name : new String[]{"موبايل نوفا A5", "موبايل نوفا X12"})
            onHost(t.host(), t.access(), "POST", "/api/v1/store/products", """
                    {"taxonomySlug":"cookware","name":"%s","variants":[{"sku":"M-%s","priceMinor":549900,"optionValues":{},"stock":[{"branchId":"%s","quantity":5}]}]}
                    """.formatted(name, uniq(), branch)).andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
    }

    @Test
    void aRequestForAKindWeSellAnsweredWithOnlyAQuestionIsWrittenAgainWithProducts() throws Exception {
        UUID id = shop();
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عشان أرشحلك الأنسب، ميزانيتك حوالين كام تقريباً؟"); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايل نوفا A5 وموبايل نوفا X12، ميزانيتك حوالين كام؟"); });
        String reply = chat.reply(id, "WEB", "ask-" + uniq(), null, "عايز موبايل").text();
        assertThat(calls.get()).as("the question-only draft was sent back").isEqualTo(2);
        assertThat(reply).contains("موبايل نوفا");
    }

    @Test
    void aReplyThatShowsProductsIsLeftAlone() throws Exception {
        UUID id = shop();
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايل نوفا A5 وموبايل نوفا X12، ميزانيتك حوالين كام؟"); });
        chat.reply(id, "WEB", "ask-" + uniq(), null, "عايز موبايل");
        assertThat(calls.get()).as("no second call").isEqualTo(1);
    }
}
