package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import com.platform.chat.ChatService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The admin's test switches: «fewer rewrites» (phase 2: code settles what it can, no second model call) and «prompt diet» (phase 3: stage rules with the message, compact catalog). */
class AiPhaseSwitchesIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ChatService chat;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    @AfterEach
    void off() { set("fewer_rewrites", null); set("prompt_diet", null); set("prompt_mode", null); }

    void set(String column, String v) {
        jdbc.sql("INSERT INTO chat.platform_ai_config (id) VALUES (1) ON CONFLICT (id) DO NOTHING").update();
        jdbc.sql("UPDATE chat.platform_ai_config SET " + column + " = :v WHERE id = 1").param("v", v).update();
        com.platform.chat.AiConfigTestAccess.forget();
    }

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
    void withFewerRewritesAVagueReplyGetsTheProductsFromCodeWithoutASecondCall() throws Exception {
        UUID id = shop();
        set("fewer_rewrites", "on");
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايلات كتير حلوة ومتنوعة يا فندم"); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايل نوفا A5"); });
        String reply = chat.reply(id, "WEB", "p2-" + uniq(), null, "عايز موبايل").text();
        assertThat(calls.get()).as("settled in code").isEqualTo(1);
        assertThat(reply).contains("موبايل نوفا").contains("5499");
    }

    @Test
    void withoutTheSwitchTheSameVagueReplyIsWrittenAgainAsBefore() throws Exception {
        UUID id = shop();
        set("fewer_rewrites", "off");
        AtomicInteger calls = new AtomicInteger();
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايلات كتير حلوة ومتنوعة يا فندم"); });
        FakeAiProvider.SCRIPT.add(turns -> { calls.incrementAndGet(); return Turn.model("عندنا موبايل نوفا A5 بـ 5499 جنيه"); });
        chat.reply(id, "WEB", "p2-" + uniq(), null, "عايز موبايل");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void withThePromptDietTheCachedPartHasTheCoreRulesAndTheStageRulesComeWithTheMessage() throws Exception {
        UUID id = shop();
        set("prompt_mode", "lean");
        set("prompt_diet", "on");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("عندنا موبايل نوفا A5 بـ 5499 جنيه"));
        chat.reply(id, "WEB", "p3-" + uniq(), null, "عندكم موبايلات؟");
        assertThat(FakeAiProvider.lastRawSystem).contains("- THIS CHAT'S RULES:").contains("- MONEY").doesNotContain("- SELLING:").doesNotContain("- ORDER:")
                .contains("موبايل نوفا A5").doesNotContain(", category ");
        assertThat(FakeAiProvider.lastSystem).contains("THIS CHAT'S RULES (stage: browsing)").contains("- SELLING:");
    }
}
