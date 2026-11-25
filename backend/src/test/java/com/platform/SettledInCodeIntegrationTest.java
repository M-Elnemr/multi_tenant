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

/** Code settles what it can without a second model call (always on since the 2026-11-25 A/B). */
class SettledInCodeIntegrationTest extends IntegrationTestBase {
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
    void aVagueReplyGetsTheProductsFromCodeWithoutASecondCall() throws Exception {
        UUID id = shop();
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايلات كتير حلوة ومتنوعة يا فندم"); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايل نوفا A5"); });
        String reply = chat.reply(id, "WEB", "settled-" + uniq(), null, "عايز موبايل").text();
        assertThat(calls.get()).as("settled in code").isEqualTo(1);
        assertThat(reply).contains("موبايل نوفا").contains("5499");
    }
}
